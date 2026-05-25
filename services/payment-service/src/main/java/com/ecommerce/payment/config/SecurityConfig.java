package com.ecommerce.payment.config;

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
 * Endpoint matrix:
 * <ul>
 *   <li>{@code POST /payments}            — auth required, role USER. Tạo PaymentIntent.</li>
 *   <li>{@code POST /payments/callback}   — <b>PUBLIC</b>. Bảo vệ bằng HMAC
 *                                            signature (X-Signature header).
 *                                            Gateway VNPay/Momo không có JWT.</li>
 *   <li>{@code GET  /payments/{id}}       — auth required.</li>
 * </ul>
 *
 * <p>Lý do callback public: gateway egress chỉ biết URL + HMAC secret;
 * không có cách inject JWT. Nhiều team sai khi ép JWT lên callback → gateway
 * không gọi được → callback miss → reconciliation alarm.
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
                        .requestMatchers(HttpMethod.POST, "/payments/callback").permitAll()
                        // Day 12 demo (dev profile only — remove khi go-prod).
                        .requestMatchers("/debug/gateway/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
