package com.ecom.lab;

import com.ecom.lab.lock.LockThroughputBenchmark;
import com.ecom.lab.vthread.VirtualVsPlatformBenchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Entry point chạy JMH programmatic — KHÔNG cần Gradle JMH plugin.
 *
 * <p>{@code ./gradlew :concurrency-lab:run}
 *
 * <p>Forked benchmark JVM nhận {@code --enable-preview} vì class trong module
 * này được compile với preview bit (do StructuredTaskScope ở package structured).
 * Truyền filter qua args để chạy 1 nhóm: {@code run --args="Lock"}.
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {
    }

    public static void main(String[] args) throws RunnerException {
        OptionsBuilder builder = new OptionsBuilder();
        if (args.length > 0) {
            builder.include(args[0]);
        } else {
            builder.include(LockThroughputBenchmark.class.getSimpleName());
            builder.include(VirtualVsPlatformBenchmark.class.getSimpleName());
        }
        Options opt = builder
            .jvmArgsAppend("--enable-preview")
            .build();
        new Runner(opt).run();
    }
}
