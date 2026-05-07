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
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

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
    }
}
