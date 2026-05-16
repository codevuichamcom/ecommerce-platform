package com.ecom.cart.config;

import com.ecom.common.security.JwtAuthenticationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Cart endpoint cho phép cả authenticated user lẫn anonymous (qua header
 * {@code X-Cart-Token}). Logic resolve ID nằm ở {@code CartIdResolver}, KHÔNG
 * ở Spring Security — vì anonymous với token là trạng thái hợp lệ, không
 * phải auth-failure.
 *
 * <p>Endpoint duy nhất require auth bắt buộc: {@code POST /cart/merge} —
 * cần biết userId để merge vào.
 *
 * <p>Day 7: JWT filter auto-config qua common-lib
 * {@code SecurityAutoConfiguration}. Cart-specific properties giữ nguyên.
 */
@Configuration
@EnableConfigurationProperties(CartProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/cart/merge").authenticated()
                        .requestMatchers("/cart/**").permitAll()    // resolver tự enforce có ID
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
