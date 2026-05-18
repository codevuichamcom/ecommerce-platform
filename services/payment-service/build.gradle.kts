/*
 * payment-service — Mock payment gateway integration (Layered).
 *
 * Day 10 deliverable. Endpoints:
 *   - POST /payments           initiate PaymentIntent (INITIATED)
 *   - POST /payments/callback  mock gateway callback (idempotent dedup)
 *   - GET  /payments/{id}      query state
 *
 * Architectural choice — Layered, KHÔNG full DDD (xem ADR-007).
 * 3-điểm criteria (CLAUDE.md §5): 1 invariant chính + ít domain event
 * cross-aggregate → không đủ ≥3 → Layered. Vẫn dùng sealed `PaymentStatus`
 * vì state machine có 5 trạng thái + exhaustive transition (CLAUDE.md
 * KHÔNG cấm Layered dùng sealed).
 *
 * Idempotency strategy: UNIQUE(provider, provider_txn_id) DB constraint
 * là source of truth (xem issue 10). App-level cache là L1 optimization
 * cho Day 15.
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

    // Day 10 — Kafka producer publish payment.completed.
    implementation("org.springframework.kafka:spring-kafka")

    // Day 4 retry pattern reuse (optimistic lock retry on concurrent callback).
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")

    // JWT verify only — admin endpoint GET /payments/{id} cần auth.
    // Callback endpoint POST /payments/callback PUBLIC (gateway không có JWT),
    // bảo vệ bằng HMAC signature trong SecurityConfig.
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

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
 * Testcontainers + Docker Desktop on Windows — pattern thống nhất với
 * auth/product/inventory/cart/order. IT guard bằng
 * RUN_PAYMENT_INTEGRATION_TESTS=true → CI Windows local default skip.
 */
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        environment("DOCKER_API_VERSION", "1.45")
    }
}
