/*
 * inventory-service — Stock aggregate (DDD selective).
 *
 * Day 4 deliverable. Endpoints: reserve / release / get stock.
 *
 * Modernity introduce:
 *   - Optimistic locking (@Version inherit từ common-lib BaseEntity) +
 *     Spring Retry @Retryable cho ObjectOptimisticLockingFailureException.
 *   - DDD Aggregate root: invariant `reserved ≤ quantity` enforce trong
 *     Stock.reserve()/release(), service KHÔNG được leak rule.
 *   - Domain events qua AbstractAggregateRoot (Spring Data) — Day 9 wire
 *     vào Kafka outbox.
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
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.retry:spring-retry")

    // Day 9 — consume order.created, publish inventory.reserved.
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

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
    }
}

/*
 * Testcontainers + Docker Desktop on Windows (xem auth/product-service):
 * pipe + tắt Ryuk + pin Docker API. Concurrency IT guard bằng
 * RUN_INVENTORY_INTEGRATION_TESTS=true (default skip cho local Windows).
 */
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        environment("DOCKER_API_VERSION", "1.45")
    }
}
