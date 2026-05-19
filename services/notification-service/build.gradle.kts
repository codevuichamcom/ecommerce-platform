/*
 * notification-service — Day 11: multi-topic consumer + Thymeleaf + API versioning.
 *
 * Thêm so với Day 8 scaffold:
 *   - spring-boot-starter-web: expose /api/v1 + /api/v2 versioning demo endpoints.
 *   - spring-boot-starter-thymeleaf: render email template (order-confirmed, payment-completed).
 *   - spring-boot-starter-data-redis: Redis SET NX idempotency dedup by eventId (TTL 24h).
 *
 * KHÔNG cần: security (no auth trên internal notification API), data-jpa
 * (notification stateless — không persist sent log, Day 34 system design sẽ thêm).
 */

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

dependencies {
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
