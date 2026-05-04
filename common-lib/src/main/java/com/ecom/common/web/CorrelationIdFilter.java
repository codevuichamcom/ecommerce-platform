package com.ecom.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Đảm bảo MỌI request đều có một correlation/trace id.
 *
 * <p>Cơ chế:
 * <ol>
 *   <li>Đọc header {@code X-Correlation-Id}. Nếu có → dùng (request được
 *       trace từ gateway xuyên service).</li>
 *   <li>Nếu không → sinh UUID mới.</li>
 *   <li>Set vào {@link MDC} với key {@code traceId} → mọi log line
 *       trong request đều có trace id (cấu hình ở logback pattern).</li>
 *   <li>Set lại vào response header → client/Postman thấy được.</li>
 *   <li>Clear MDC ở finally để tránh leak qua thread pool.</li>
 * </ol>
 *
 * <p>Order = HIGHEST_PRECEDENCE để chạy TRƯỚC mọi filter của Spring
 * Security. Nếu để sau, log của security exceptions sẽ thiếu traceId.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
