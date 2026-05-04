/*
 * Root build cho ecommerce-platform.
 *
 * Triết lý:
 *   - Root KHÔNG apply spring-boot plugin (không phải app).
 *   - Cấu hình chung cho mọi subproject ở `subprojects { ... }`:
 *       * Java toolchain 21
 *       * UTF-8 + -parameters
 *       * JUnit 5 platform
 *       * Lombok + MapStruct annotation processors (chỉ apply nếu module
 *         có dùng — kiểm tra ở build.gradle.kts từng module).
 *   - Mỗi service tự apply spring-boot + dependency-management plugin.
 *
 * Tham khảo version: gradle/libs.versions.toml
 */

plugins {
    java
    alias(libs.plugins.spring.boot)              apply false
    alias(libs.plugins.spring.dependency.mgmt)   apply false
}

allprojects {
    group = "com.ecom"
    version = "1.0.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -parameters: giữ tên parameter trong bytecode → Spring/JPA/Jackson
        // ánh xạ field name không bị "arg0".
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        // Stable Values + virtual threads benchmark sẽ chạy ở Day 19.
        systemProperty("file.encoding", "UTF-8")
    }

    // Repositories được khai báo tập trung ở settings.gradle.kts
    // (dependencyResolutionManagement với FAIL_ON_PROJECT_REPOS).
    // KHÔNG khai báo lại ở đây — sẽ fail build.
}
