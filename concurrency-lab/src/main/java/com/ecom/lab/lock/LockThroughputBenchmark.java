package com.ecom.lab.lock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * So sánh 3 cơ chế mutual-exclusion cho 1 read-mostly critical section
 * (đọc 2 field, cộng lại) dưới contention 8 thread.
 *
 * <p>Điểm cần thấy qua số đo:
 * <ul>
 *   <li>{@code synchronized} — JIT biased/thin lock, nhanh khi ít contention,
 *       degrade khi nhiều thread tranh.</li>
 *   <li>{@link ReentrantLock} — explicit, fairness configurable, nhưng vẫn là
 *       mutual exclusion → reader chặn reader.</li>
 *   <li>{@link StampedLock#tryOptimisticRead()} — đọc KHÔNG khoá, chỉ
 *       {@code validate()} sau; reader không chặn reader → thắng đậm read-heavy.
 *       Cái giá: KHÔNG reentrant, phải copy field ra local trước khi validate.</li>
 * </ul>
 *
 * <p>Chạy: {@code ./gradlew :concurrency-lab:run} (qua BenchmarkRunner).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(8)
public class LockThroughputBenchmark {

    private int x = 1;
    private int y = 2;

    private final Object monitor = new Object();
    private final ReentrantLock reentrant = new ReentrantLock();
    private final StampedLock stamped = new StampedLock();

    @Benchmark
    public int synchronizedRead() {
        synchronized (monitor) {
            return x + y;
        }
    }

    @Benchmark
    public int reentrantLockRead() {
        reentrant.lock();
        try {
            return x + y;
        } finally {
            reentrant.unlock();
        }
    }

    /**
     * Optimistic read pattern CHUẨN: đọc field ra local TRƯỚC, validate SAU.
     * Nếu validate fail (có writer xen vào) → fallback sang read lock thật.
     * Bỏ bước copy-trước-validate = đọc state torn → bug kinh điển.
     */
    @Benchmark
    public int stampedOptimisticRead() {
        long stamp = stamped.tryOptimisticRead();
        int cx = x;
        int cy = y;
        if (!stamped.validate(stamp)) {
            stamp = stamped.readLock();
            try {
                cx = x;
                cy = y;
            } finally {
                stamped.unlockRead(stamp);
            }
        }
        return cx + cy;
    }
}
