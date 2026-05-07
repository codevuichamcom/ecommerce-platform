package com.ecom.auth.security;

import com.ecom.auth.service.JwtService;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.response.ApiResponse;
import com.ecom.common.response.ApiResponse.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.List;

/**
 * Extract Bearer token, verify via {@link JwtService}, set SecurityContext.
 *
 * <p>Skip nếu:
 * <ul>
 *   <li>Path là public (configured ở {@link com.ecom.auth.config.SecurityConfig}
 *       — filter vẫn run nhưng không set context, security chain cho
 *       qua nhờ {@code permitAll()}).</li>
 *   <li>Header thiếu / không bắt đầu bằng "Bearer ".</li>
 * </ul>
 *
 * <p>Khi token EXPIRED hoặc INVALID → ghi vào response 401 + JSON
 * envelope (KHÔNG để Spring default trả empty body, frontend khó debug).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

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
            AuthUserPrincipal principal = jwtService.verify(token);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (BusinessException ex) {
            // Clear context để filter sau không thấy stale auth.
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
