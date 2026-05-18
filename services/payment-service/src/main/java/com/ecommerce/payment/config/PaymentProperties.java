package com.ecommerce.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình HMAC + replay window cho mock gateway callback.
 *
 * <p>Production: {@code callbackSecret} từ secret manager (Vault / AWS SM /
 * GCP Secret Manager), per-provider key. Day 10 single secret cho mock.
 */
@Configuration
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    private String callbackSecret = "dev-only-callback-secret-change-me";
    private long callbackMaxSkewSeconds = 300; // 5 phút

    public String getCallbackSecret() { return callbackSecret; }
    public void setCallbackSecret(String v) { this.callbackSecret = v; }

    public long getCallbackMaxSkewSeconds() { return callbackMaxSkewSeconds; }
    public void setCallbackMaxSkewSeconds(long v) { this.callbackMaxSkewSeconds = v; }
}
