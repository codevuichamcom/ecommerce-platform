package com.ecommerce.notification.template;

/**
 * Rendered notification payload — channel-agnostic.
 * Channel impl (email, SMS, push) nhận record này và dispatch theo kênh riêng.
 */
public record NotificationPayload(
        String recipient,  // email address hoặc phone number hoặc device token
        String subject,
        String body        // HTML hoặc plain text tùy channel
) {}
