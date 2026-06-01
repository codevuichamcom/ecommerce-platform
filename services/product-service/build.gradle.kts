/*
 * product-service — Product / Category catalog (Layered architecture).
 *
 * Day 3 deliverable. Endpoints: CRUD product + category, search LIKE basic,
 * offset pagination. Search sẽ tune index ở Day 16, migrate sang ES Day 22.
 *
 * Modernity introduce:
 *   - MapStruct 1.6 cho DTO mapping (compile-time, không reflection).
 *   - Records cho DTO request/response.
 *   - Hibernate 6.6 native JSONB mapping (@JdbcTypeCode SqlTypes.JSON) cho
 *     `attributes` flexible — không cần hibernate-types dependency.
 *   - JWT validate dùng cùng HMAC secret với auth-service (Day 7 sẽ
 *     refactor `JwtVerifier` lên common-lib hoặc chuyển JWKS).
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

    // Day 15 — 2-tier cache stack.
    // - starter-cache: Spring Cache abstraction (@Cacheable / @CacheEvict).
    // - starter-data-redis: L2 distributed cache (Lettuce client, non-blocking,
    //   share connection an toàn với virtual threads).
    // - caffeine: L1 in-process cache (Caffeine 3, near-optimal hit ratio
    //   vs LRU vanilla; native recordStats() bind tới Micrometer).
    // - starter-actuator + registry-prometheus: expose `/actuator/prometheus`
    //   cho cache.gets / cache.puts metrics (Day 20 sẽ wire Grafana board).
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation(libs.caffeine)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Day 22 — Elasticsearch 8 full-text search.
    // - starter-data-elasticsearch: Spring Data ES 5.4 + ES Java client 8.15
    //   (managed bởi Boot 3.4.5 BOM — KHÔNG pin version tay). Repository +
    //   ElasticsearchOperations + NativeQuery DSL.
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // Day 22 — Kafka cho sync Postgres → ES (CDC-lite app-level).
    // common-lib KafkaAutoConfiguration opt-in qua app.kafka.enabled=true.
    // product-service VỪA producer (publish product.upserted/deleted) VỪA
    // consumer (ProductIndexer index document vào ES).
    implementation("org.springframework.kafka:spring-kafka")

    // JWT verify only (không issue token — đó là auth-service job).
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // MapStruct — compile-time mapping codegen.
    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)

    // Lombok + ordering trick: lombok-mapstruct-binding để MapStruct hiểu
    // được Lombok-generated getter/setter. Thứ tự AP quan trọng:
    // lombok TRƯỚC mapstruct.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.lombok.mapstruct.binding)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.bundles.testcontainers.default)
    // Day 22 — ES integration test (gated RUN_PRODUCT_INTEGRATION_TESTS=true).
    testImplementation(libs.testcontainers.elasticsearch)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

dependencyManagement {
    imports {
        mavenBom(libs.testcontainers.bom.get().toString())
    }
}

/*
 * Testcontainers + Docker Desktop on Windows (xem auth-service build.gradle.kts):
 * pipe + tắt Ryuk + pin Docker API. Integration test guard bằng
 * RUN_PRODUCT_INTEGRATION_TESTS=true (default skip cho local Windows).
 */
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        environment("DOCKER_API_VERSION", "1.45")
    } else {
        // Linux dev/CI: docker-java mặc định negotiate API 1.32, nhưng daemon
        // mới (Docker Engine 25+) yêu cầu tối thiểu 1.40 → "client version too
        // old". Pin API version (cả env lẫn system property docker-java đọc)
        // để Testcontainers connect được (Day 22 ES IT). KHÔNG override
        // DOCKER_HOST — để Testcontainers auto-detect /var/run/docker.sock.
        environment("DOCKER_API_VERSION", "1.43")
        systemProperty("api.version", "1.43")
    }
}
