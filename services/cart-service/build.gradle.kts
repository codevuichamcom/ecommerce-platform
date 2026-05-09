/*
 * cart-service — Redis-backed shopping cart (Layered).
 *
 * Day 5 deliverable. Endpoints: add / update / remove / clear / get / merge.
 *   - Redis là PRIMARY store (không Postgres). Hash structure per cart, TTL 7d.
 *   - HINCRBY atomic ở field-level → chống lost-update khi 2 tab cùng add 1 SKU.
 *   - JWT verify reuse pattern từ product-service (sẽ lift lên common-lib Day 7).
 *   - Anonymous cart qua header `X-Cart-Token` → user cart sau login (POST /cart/merge).
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
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // JWT verify only (giống product-service).
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.spring.boot.testcontainers)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

dependencyManagement {
    imports {
        mavenBom(libs.testcontainers.bom.get().toString())
    }
}

/*
 * Testcontainers + Docker Desktop on Windows: pattern giống auth/product service.
 * Integration test (Redis container + concurrency 100-thread) gated bằng
 * RUN_CART_INTEGRATION_TESTS=true để CI Windows local default skip.
 */
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        environment("DOCKER_API_VERSION", "1.45")
    }
}
