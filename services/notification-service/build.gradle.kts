/*
 * notification-service — Kafka consumer demo (Day 8 scaffold).
 *
 * Day 8 scope:
 *   - Boot up + 1 @KafkaListener log `order.created` event nhận được.
 *   - Verify end-to-end Kafka pipeline (order-service publish → broker → consumer).
 *
 * Day 11 sẽ build full:
 *   - Multi-topic listener (order.*, payment.*, notification.outgoing)
 *   - Thymeleaf template engine
 *   - Email/SMS/Push adapter (mock SMTP/Twilio)
 *   - Idempotent handler (dedup by eventId)
 *
 * KHÔNG cần: web starter (consumer-only, không expose REST endpoint),
 * data-jpa (Day 11 mới thêm khi cần persist sent log), security (consumer
 * không có inbound HTTP).
 */

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

dependencies {
    implementation(project(":common-lib"))

    // Core + actuator (health endpoint) — KHÔNG web/security/jpa.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
