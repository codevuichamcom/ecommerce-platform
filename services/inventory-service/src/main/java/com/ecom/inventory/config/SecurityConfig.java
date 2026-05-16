package com.ecom.inventory.config;

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
 * Stateless JWT verify only.
 *
 * <p>Endpoint matrix:
 * <ul>
 *   <li>{@code GET /inventory/{sku}} — auth required, role ADMIN/SERVICE.
 *       KHÔNG public vì stock level là sensitive (competitor scrape).</li>
 *   <li>{@code POST /inventory/reserve} — chỉ SERVICE (cart/order).</li>
 *   <li>{@code POST /inventory/release} — chỉ SERVICE (cart/order).</li>
 * </ul>
 *
 * <p>Day 7: filter + verifier + props auto-config qua common-lib
 * {@code SecurityAutoConfiguration}. Day 8 sẽ thay đổi: cross-service
 * auth dùng mTLS hoặc service token, không reuse user JWT.
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
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
