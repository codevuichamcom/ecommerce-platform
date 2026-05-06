package com.ecom.auth.config;

import com.ecom.auth.security.JwtAuthenticationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT auth setup.
 *
 * <p>Quy ước:
 * <ul>
 *   <li>{@code /auth/register, /auth/login, /auth/refresh} — public.</li>
 *   <li>Tất cả còn lại require Bearer token (filter đặt trước
 *       {@link UsernamePasswordAuthenticationFilter}).</li>
 *   <li>CSRF disabled — stateless, không có cookie session.</li>
 *   <li>BCrypt cost mặc định = 10. Prod nên đo trên hardware thật để
 *       chỉnh ~250ms/hash (xem lessons/02-jwt-vs-session.md).</li>
 * </ul>
 *
 * <p>Lưu ý security review: 401 → JSON via {@link HttpStatusEntryPoint}
 * thay vì redirect login form (default Spring). Frontend chỉ cần đọc
 * status code, không bị redirect làm vỡ SPA.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())  // Day 26 frontend sẽ enable + config origin
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // strength=10 — adaptive, slow by design. Day 19 sẽ benchmark.
        return new BCryptPasswordEncoder(10);
    }
}
