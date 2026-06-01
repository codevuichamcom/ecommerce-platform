/*
 * concurrency-lab — Day 19 sandbox cho concurrency primitives.
 *
 * Mục đích KHÔNG phải 1 service chạy production, mà là nơi ĐO:
 *   - JMH microbenchmark: synchronized vs ReentrantLock vs StampedLock
 *   - JMH: Virtual Thread vs Platform Thread cho IO-bound workload
 *   - Pinning demo (main + JFR) — VT bị pin vì `synchronized` quanh blocking call
 *   - Structured Concurrency (StructuredTaskScope, JEP 453 preview) fan-out
 *
 * Vì sao module RIÊNG?
 *   `StructuredTaskScope` là PREVIEW API ở Java 21 → cần `--enable-preview`.
 *   Bật cờ này ở service production sẽ đóng dấu preview bit lên mọi class →
 *   buộc runtime JVM cũng phải `--enable-preview` (rủi ro ops). Cô lập ở đây.
 *
 * Vì sao JMH KHÔNG dùng Gradle plugin (me.champeau.jmh)?
 *   Plugin phải resolve từ plugin portal. Ta giữ build offline-friendly bằng
 *   cách kéo `jmh-core` + annotation processor như dependency thường, rồi chạy
 *   benchmark programmatic qua `Runner` ở BenchmarkRunner.main().
 */

plugins {
    java
    application
}

dependencies {
    // JMH: annotation processor sinh code benchmark lúc compile main source set.
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.annprocess)

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // `./gradlew :concurrency-lab:run` mặc định chạy bộ benchmark.
    mainClass.set("com.ecom.lab.BenchmarkRunner")
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

// --enable-preview cho compile (StructuredTaskScope) + mọi task chạy code.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview")
}

tasks.named<JavaExec>("run") {
    jvmArgs("--enable-preview")
}

// Task tường minh để chạy demo pinning (cần cờ JFR + tracePinnedThreads).
tasks.register<JavaExec>("runPinningDemo") {
    group = "verification"
    description = "Reproduce virtual-thread pinning + JFR detection"
    mainClass.set("com.ecom.lab.pinning.PinningDemo")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-preview", "-Djdk.tracePinnedThreads=full")
}
