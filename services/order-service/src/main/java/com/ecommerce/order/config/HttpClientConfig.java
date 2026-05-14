package com.ecommerce.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Tunable timeout cho cross-service RestClient. Connect 2s + read 5s —
 * conservative cho local dev. Production sẽ giảm xuống 1s/3s + thêm
 * Resilience4j circuit breaker (Day 12).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return RestClient.builder().requestFactory(factory);
    }
}
