/*
 * Multi-project layout cho ecommerce-platform.
 *
 * Bật:
 *   - typesafe project accessors:    project(:common-lib) → projects.commonLib
 *   - Foojay toolchain resolver:     auto-download JDK 21 nếu local không có
 *   - configuration cache:           xem gradle.properties
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Tự resolve JVM toolchain — không bắt dev cài JDK 21 thủ công.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ecommerce-platform"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include("common-lib")

// Day 19 — JMH microbenchmark + Structured Concurrency (preview) lab.
// Isolate ở module riêng để `--enable-preview` KHÔNG lan ra service production.
include("concurrency-lab")

include("services:auth-service")
include("services:product-service")
include("services:inventory-service")
include("services:cart-service")
include("services:order-service")
include("services:notification-service")
include("services:payment-service")

// Services sẽ uncomment khi bắt đầu build:
// include("services:gateway-service")
// include("services:analytics-service")
