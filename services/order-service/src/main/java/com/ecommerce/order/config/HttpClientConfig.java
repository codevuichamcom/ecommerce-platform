package com.ecommerce.order.config;

import com.ecommerce.order.infrastructure.client.ProductHttpInterfaceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

/**
 * Tunable timeout cho cross-service RestClient. Connect 2s + read 5s —
 * conservative cho local dev. Production sẽ giảm xuống 1s/3s + thêm
 * Resilience4j circuit breaker (Day 12).
 *
 * <p>Day 8 cũng wire {@link ProductHttpInterfaceClient} qua
 * {@link HttpServiceProxyFactory} + {@link RestClientAdapter}. Đây là
 * 1 ví dụ "boilerplate" so với Feign — Feign chỉ cần {@code @EnableFeignClients}
 * tự scan interface. Trade-off nói trong ADR-005.
 */
@Configuration
@EnableFeignClients(basePackages = "com.ecommerce.order.infrastructure.client")
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public ProductHttpInterfaceClient productHttpInterfaceClient(
            RestClient.Builder builder,
            @Value("${services.product.base-url}") String baseUrl) {
        RestClient restClient = builder.baseUrl(baseUrl).build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
        return factory.createClient(ProductHttpInterfaceClient.class);
    }
}
