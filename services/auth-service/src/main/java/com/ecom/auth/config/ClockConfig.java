package com.ecom.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * {@link Clock} bean — inject vào service thay vì gọi {@code Instant.now()}
 * thẳng. Test có thể override bằng {@link Clock#fixed} để kiểm tra
 * expired-token path mà không cần {@code Thread.sleep}.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
