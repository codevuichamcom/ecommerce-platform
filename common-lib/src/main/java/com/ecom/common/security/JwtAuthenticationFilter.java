package com.ecom.common.security;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.response.ApiResponse;
import com.ecom.common.response.ApiResponse.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Verify-only JWT filter dùng chung cho 4 service:
 * product / inventory / cart / order.
 *
 * <p>Hành vi:
 * <ul>
 *   <li>Header thiếu / không bắt đầu {@code Bearer } → bỏ qua, để
 *       SecurityConfig quyết permit/deny (cho phép anonymous endpoint).</li>
 *   <li>Token invalid / expired → 401 + JSON envelope (
 *       {@link ApiResponse}), KHÔNG để Spring default trả empty body.</li>
 * </ul>
 *
 * <p>Auto-register qua {@code SecurityAutoConfiguration} với
 * {@code @ConditionalOnMissingBean} — service nào cần custom filter
 * (vd: cross-service mTLS Day 8) override bằng cách declare bean cùng
 * type ở {@code @Configuration} của service.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtVerifier jwtVerifier, ObjectMapper objectMapper) {
        this.jwtVerifier = jwtVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length());

        try {
            AuthUserPrincipal principal = jwtVerifier.verify(token);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (BusinessException ex) {
            SecurityContextHolder.clearContext();
            writeError(response, ex);
        }
    }

    private void writeError(HttpServletResponse response, BusinessException ex) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError err = new ApiError(ex.getErrorCode().name(), ex.getMessage(), null);
        ApiResponse<Void> body = ApiResponse.error(err, MDC.get("traceId"));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
