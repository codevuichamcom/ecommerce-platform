package com.ecom.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật {@code @Scheduled} cho daily inventory snapshot job (Day 19).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
