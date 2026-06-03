/*
 * analytics-service — Day 23: MongoDB event store + aggregation report.
 *
 * VÌ SAO LÀ SERVICE RIÊNG (không nhét analytics vào order/product)?
 *   - Workload đối lập: OLTP service (order/product) cần low-latency + ACID;
 *     analytics là OLAP-lite (write-heavy append, read = aggregation nặng).
 *     Trộn chung → aggregation query khoá/ngốn IO của transactional DB.
 *   - Storage khác: analytics dùng Mongo (schemaless event + TTL), không
 *     phải Postgres. DB-per-service rule (CLAUDE.md §5) → service riêng.
 *
 * Stack tối thiểu (CÓ CHỦ Ý, không kéo dư):
 *   - data-mongodb: event store + aggregation pipeline + index.
 *   - web: tracking beacon endpoint (POST /analytics/track) + report API.
 *   - spring-kafka: consume domain event (order.created) làm event nguồn.
 *
 * KHÔNG cần: data-jpa (không đụng Postgres), security (internal service,
 * gateway sẽ chắn ở Day sau), redis.
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
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Day 23 — MongoDB 7. MongoRepository + MongoTemplate (aggregation DSL)
    // + @Document mapping. Version managed bởi Boot 3.4.5 BOM (Mongo driver
    // 5.x) — KHÔNG pin tay.
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")

    // Consume order.created (event nguồn cho funnel stage "purchased").
    // common-lib KafkaAutoConfiguration opt-in qua app.kafka.enabled=true.
    implementation("org.springframework.kafka:spring-kafka")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.bundles.testcontainers.default)
    // Day 23 — Mongo integration test (gated RUN_ANALYTICS_INTEGRATION_TESTS=true).
    testImplementation(libs.testcontainers.mongodb)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

dependencyManagement {
    imports {
        mavenBom(libs.testcontainers.bom.get().toString())
    }
}

/*
 * Testcontainers Docker API pin — giống product-service Day 22. Linux daemon
 * mới yêu cầu API ≥ 1.40 nhưng docker-java negotiate 1.32 → pin 1.43.
 * Integration test gated RUN_ANALYTICS_INTEGRATION_TESTS=true (default skip).
 */
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        environment("DOCKER_API_VERSION", "1.45")
    } else {
        environment("DOCKER_API_VERSION", "1.43")
        systemProperty("api.version", "1.43")
    }
}
