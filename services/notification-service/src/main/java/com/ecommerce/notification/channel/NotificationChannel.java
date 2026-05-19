package com.ecommerce.notification.channel;

import com.ecommerce.notification.template.NotificationPayload;

/**
 * Abstraction cho dispatch layer — tách channel concern khỏi consumer logic.
 *
 * <p>Day 11: implement {@link LoggingEmailChannel} (log-only).
 * Day 34 system design: thêm {@code SmtpEmailChannel}, {@code SmsChannel},
 * {@code FcmPushChannel} mà không sửa consumer.
 */
public interface NotificationChannel {

    void send(NotificationPayload payload);
}
