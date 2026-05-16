package com.ecom.common.autoconfig;

import com.ecom.common.security.JwtAuthenticationFilter;
import com.ecom.common.security.JwtVerifier;
import com.ecom.common.security.JwtVerifyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Auto-config cho verify-only JWT stack.
 *
 * <p>Activate khi:
 * <ul>
 *   <li>Có {@code io.jsonwebtoken.Jwts} trên classpath (jjwt dependency).</li>
 *   <li>Service có Spring Security Web ({@link UsernamePasswordAuthenticationFilter}).</li>
 *   <li>Property {@code auth.jwt.secret} được set (filter ở opt-in mode —
 *       service KHÔNG dùng JWT verify như notification-service Day 11
 *       sẽ skip toàn bộ).</li>
 * </ul>
 *
 * <p>Service muốn override filter (vd: thêm role-claim mapper riêng)
 * declare bean cùng type → {@code @ConditionalOnMissingBean} cho qua.
 *
 * <p>Auth-service KHÔNG dùng auto-config này — auth-service principal
 * có 4 field (kèm {@code tokenVersion}) và tự xử filter qua
 * {@code com.ecom.auth.security.JwtAuthenticationFilter}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({Jwts.class, UsernamePasswordAuthenticationFilter.class})
@ConditionalOnProperty(prefix = "auth.jwt", name = "secret")
@EnableConfigurationProperties(JwtVerifyProperties.class)
public class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtVerifier jwtVerifier(JwtVerifyProperties props) {
        return new JwtVerifier(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtVerifier verifier,
                                                           ObjectMapper objectMapper) {
        return new JwtAuthenticationFilter(verifier, objectMapper);
    }
}
