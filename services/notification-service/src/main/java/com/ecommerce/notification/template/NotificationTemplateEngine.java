package com.ecommerce.notification.template;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Thin wrapper quanh Thymeleaf {@link TemplateEngine}.
 *
 * <p>Template resolution: Spring Boot auto-configure Thymeleaf với
 * {@code prefix=classpath:/templates/notification/} + {@code suffix=.html}
 * (xem application.yml). Template tên "order-created" → file
 * {@code classpath:/templates/notification/order-created.html}.
 *
 * <p>Security note: LUÔN dùng {@code th:text} (auto-escape HTML) trong
 * template. KHÔNG dùng {@code th:utext} với dữ liệu từ user — XSS risk.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationTemplateEngine {

    private final TemplateEngine templateEngine;

    /**
     * Render Thymeleaf template thành HTML string.
     *
     * @param templateName tên template (không có prefix/suffix), vd "order-created"
     * @param variables    biến truyền vào template context
     * @return rendered HTML body
     */
    public String render(String templateName, Map<String, Object> variables) {
        Context ctx = new Context();
        ctx.setVariables(variables);
        String html = templateEngine.process(templateName, ctx);
        log.debug("[template] rendered template={} chars={}", templateName, html.length());
        return html;
    }
}
