package com.ecommerce.order.config;

import com.ecom.common.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT verify. Endpoint matrix:
 * <ul>
 *   <li>{@code POST /orders} — auth required, role USER.</li>
 *   <li>{@code GET /orders/{id}} — auth required, role USER (own order)
 *       hoặc ADMIN. Day 6 chỉ check authenticated, ownership check ở
 *       application layer.</li>
 *   <li>{@code POST /orders/{id}/cancel} — auth required, role USER (own)
 *       hoặc ADMIN.</li>
 * </ul>
 *
 * <p>Day 7: filter auto-config qua common-lib {@code SecurityAutoConfiguration}.
 */
@Configuration
@EnableMethodSecurity
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
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()
                        // Day 8 demo endpoint — sẽ xóa ở Day 9 khi wire publish vào use case thật.
                        .requestMatchers("/debug/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
