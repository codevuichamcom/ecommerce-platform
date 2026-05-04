package com.ecom.common.autoconfig;

import com.ecom.common.audit.AuditorAwareImpl;
import com.ecom.common.exception.GlobalExceptionHandler;
import com.ecom.common.web.CorrelationIdFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import jakarta.persistence.EntityManagerFactory;

/**
 * Auto-config gốc của {@code common-lib}. Service nào add dependency
 * {@code common-lib} sẽ tự nhận:
 * <ul>
 *   <li>{@link CorrelationIdFilter} — nếu là web app.</li>
 *   <li>{@link GlobalExceptionHandler} — nếu là web app.</li>
 *   <li>{@code @EnableJpaAuditing} + {@link AuditorAwareImpl} — nếu có JPA.</li>
 * </ul>
 *
 * <p>Pattern này = "library mà behave như starter": muốn opt-out chỉ
 * cần override bean. Đỡ boilerplate copy/paste cho 9 services.
 */
@Configuration(proxyBeanMethods = false)
public class CommonAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    static class WebSupport {

        @Bean
        @ConditionalOnMissingBean
        public CorrelationIdFilter correlationIdFilter() {
            return new CorrelationIdFilter();
        }

        @Bean
        @ConditionalOnMissingBean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EntityManagerFactory.class)
    @EnableJpaAuditing(auditorAwareRef = "auditorAware")
    static class JpaAuditingSupport {

        @Bean
        @ConditionalOnMissingBean(name = "auditorAware")
        public AuditorAware<String> auditorAware() {
            return new AuditorAwareImpl();
        }
    }
}
