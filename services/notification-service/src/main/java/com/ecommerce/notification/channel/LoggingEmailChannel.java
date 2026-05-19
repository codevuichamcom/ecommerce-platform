package com.ecommerce.notification.channel;

import com.ecommerce.notification.template.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fire-and-forget email channel — log payload thay vì gửi SMTP thật.
 *
 * <p>Đây là adapter pattern: consumer không biết cụ thể email hay SMS —
 * chỉ gọi {@link NotificationChannel#send}. Khi cần SMTP thật, implement
 * {@code SmtpEmailChannel} + điều kiện bean override, KHÔNG sửa consumer.
 *
 * <p>TODO Day 34: replace bằng {@code JavaMailSenderEmailChannel} với
 * Spring Mail + retry (Resilience4j) + fallback SMS.
 */
@Slf4j
@Component
public class LoggingEmailChannel implements NotificationChannel {

    @Override
    public void send(NotificationPayload payload) {
        log.info("[email-channel] SEND to={} subject=\"{}\" bodyLength={}",
                payload.recipient(),
                payload.subject(),
                payload.body().length());
        // TODO Day 34: JavaMailSender.send(MimeMessage) với SMTP config.
    }
}
