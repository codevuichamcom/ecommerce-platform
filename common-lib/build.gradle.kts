/*
 * common-lib — shared infrastructure (auto-config starter).
 *
 * Là LIBRARY, không phải Boot app:
 *   - Apply `java-library` (đúng plugin chuẩn cho artifact share)
 *   - Apply `io.spring.dependency-management` để import Spring Boot BOM
 *     mà KHÔNG kéo theo bootJar/bootRun task (vì không apply spring-boot plugin)
 *   - `compileOnly` + `api` chia rõ: gì service tiêu dùng tự kéo, gì chỉ
 *     dùng để compile common-lib.
 */

plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    // Core Spring Boot — service consumer luôn cần
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-autoconfigure")

    // Optional integrations — service NÀO cần thì service đó tự pull
    // (nhờ vậy notification-service Kafka-only không bị kéo Web/JPA dư)
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jpa")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.security:spring-security-web")
    compileOnly("org.springframework.security:spring-security-config")

    // JWT verify-only stack (Day 7) — consumer service đã có jjwt-api/impl/jackson
    // ở build.gradle.kts riêng (auth/product/inventory/cart/order). compileOnly
    // ở đây để common-lib compile được mà không force kéo jjwt vào notification
    // service (Day 11) nếu service đó không cần JWT.
    compileOnly(libs.jjwt.api)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("common-lib")
}
