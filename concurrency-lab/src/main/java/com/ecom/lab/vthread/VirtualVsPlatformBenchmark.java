package com.ecom.lab.vthread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Virtual Thread vs Platform Thread cho workload <b>IO-bound</b>: chạy
 * {@code tasks} task, mỗi task block {@code ioMillis} (mô phỏng call DB / HTTP
 * downstream bằng {@code Thread.sleep}).
 *
 * <p>Điểm cần thấy:
 * <ul>
 *   <li>VT: 1 OS carrier thread phục vụ vô số VT đang block → 10.000 task sleep
 *       chạy gần như song song hoàn toàn, thời gian ≈ ioMillis.</li>
 *   <li>Platform pool 200: chỉ 200 task chạy đồng thời, phần còn lại xếp hàng →
 *       thời gian ≈ ioMillis × ceil(tasks / 200).</li>
 * </ul>
 *
 * <p>⚠️ CẢNH BÁO overclaim: VT thắng <i>chỉ vì</i> workload block. Nếu task là
 * CPU-bound (tính toán thuần) thì VT KHÔNG nhanh hơn — vẫn bị giới hạn ở số core.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class VirtualVsPlatformBenchmark {

    @Param({"10000"})
    public int tasks;

    @Param({"1"})
    public int ioMillis;

    @Benchmark
    public void virtualThreads() throws InterruptedException {
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            runAll(exec);
        }
    }

    @Benchmark
    public void platformPool200() throws InterruptedException {
        try (ExecutorService exec = Executors.newFixedThreadPool(200)) {
            runAll(exec);
        }
    }

    private void runAll(ExecutorService exec) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            exec.submit(() -> {
                try {
                    Thread.sleep(ioMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }
}
