package com.ecommerce.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * payment-service entrypoint.
 *
 * <p>Scan thêm {@code com.ecom.common} để pickup CorrelationIdFilter,
 * GlobalExceptionHandler, JPA auditing, SecurityAutoConfiguration,
 * KafkaAutoConfiguration — pattern thống nhất.
 *
 * <p>{@code @EnableRetry} cho phép {@code @Retryable} ở
 * {@link com.ecommerce.payment.application.HandleCallbackUseCase} — concurrent
 * callback có thể chạm optimistic lock (race giữa lookup → update). Retry
 * 3 lần exponential backoff thay vì throw 5xx khiến gateway retry chu kỳ
 * sau (gây loop).
 */
@SpringBootApplication(scanBasePackages = {"com.ecommerce.payment", "com.ecom.common"})
@EnableRetry
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
