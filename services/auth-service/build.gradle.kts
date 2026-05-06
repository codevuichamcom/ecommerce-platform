/*
 * auth-service — JWT-based authentication.
 *
 * Day 2 deliverable. Endpoints: register / login / refresh / me.
 *   - Virtual Threads enabled (spring.threads.virtual.enabled=true)
 *   - Records cho DTO request/response
 *   - Testcontainers Postgres + @ServiceConnection (Boot 3.1+)
 *   - jjwt 0.12 cho HS256 JWT (Day 2; Day 6+ có thể chuyển RS256 nếu cần multi-issuer)
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

    // JWT
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // Lombok
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
 * Testcontainers + Docker Desktop on Windows: default discovery hay miss
 * khi context = `desktop-linux`. Force engine pipe + tắt Ryuk reaper
 * (Ryuk dùng để cleanup container — gây lỗi với named pipe trên Windows;
 * trade-off: nếu test crash, container có thể leftover, prune thủ công).
 */
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        // Docker Engine 29.x server API version (1.52) chưa được docker-java
        // (shipped trong testcontainers 1.21) hỗ trợ → pin xuống version compat.
        environment("DOCKER_API_VERSION", "1.45")
    }
}
