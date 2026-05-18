/*
 * order-service — Order aggregate (DDD selective).
 *
 * Day 6 deliverable. Endpoints: place / get / cancel order.
 *
 * Modernity introduce:
 *   - Sealed interface `OrderStatus` permits 5 states + exhaustive switch
 *     pattern matching (Java 21) → compile-time check transition rule.
 *   - Aggregate root `Order` enforce invariant total = Σ(item.subtotal),
 *     lifecycle transition không bypass (no public setter).
 *   - Domain events `OrderPlaced` / `OrderCancelled` qua @DomainEvents
 *     (cùng pattern Stock Day 4) — Day 9 wire Kafka outbox.
 *   - Cross-service call qua Spring 6.1 RestClient sync (Day 8 sẽ compare
 *     với OpenFeign + HTTP Interface, Day 13 refactor sang outbox async).
 */

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

dependencies {
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Day 8 — Kafka producer + OpenFeign (compare với Spring 6.1 HTTP Interface).
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

    // JWT verify only (token issued bởi auth-service).
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.bundles.testcontainers.default)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

dependencyManagement {
    imports {
        mavenBom(libs.testcontainers.bom.get().toString())
        // Spring Cloud BOM cho OpenFeign — phải align version với Spring Boot 3.4.5
        // (Spring Cloud 2024.0.0 = Boot 3.4.x).
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.spring.cloud.get()}")
    }
}

/*
 * Testcontainers + Docker Desktop on Windows — cùng pattern auth/product/
 * inventory/cart. IT guard bằng RUN_ORDER_INTEGRATION_TESTS=true.
 */
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        environment("DOCKER_API_VERSION", "1.45")
    }
}
