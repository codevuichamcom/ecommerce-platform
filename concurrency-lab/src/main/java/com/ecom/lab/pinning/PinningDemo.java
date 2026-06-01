package com.ecom.lab.pinning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/**
 * Tái hiện + chứng minh <b>Virtual Thread pinning</b> bằng JFR thật, KHÔNG đoán.
 *
 * <p>Pinning = VT bị "đóng đinh" vào carrier (platform) thread, không thể
 * unmount khi block. Hai nguyên nhân chính ở Java 21:
 * <ol>
 *   <li>block (vd {@code Thread.sleep}, IO) khi đang ở trong khối
 *       {@code synchronized} → monitor gắn vào carrier;</li>
 *   <li>gọi native method (JNI).</li>
 * </ol>
 *
 * <p>Demo chạy 2 lần cùng workload (200 VT, mỗi VT block 50ms):
 * <ul>
 *   <li>{@link #synchronizedWorkload} — block TRONG {@code synchronized} → pin.</li>
 *   <li>{@link #reentrantWorkload} — thay bằng {@link ReentrantLock} → KHÔNG pin
 *       (ReentrantLock park qua {@code LockSupport} → VT unmount bình thường).</li>
 * </ul>
 * Đếm event {@code jdk.VirtualThreadPinned} từ JFR recording để so sánh.
 *
 * <p>Chạy: {@code ./gradlew :concurrency-lab:runPinningDemo}
 * (đã set {@code -Djdk.tracePinnedThreads=full} để in stacktrace pin ra stderr).
 */
public final class PinningDemo {

    private static final int VTHREADS = 200;
    private static final long BLOCK_MILLIS = 50;

    private static final Object MONITOR = new Object();
    private static final ReentrantLock LOCK = new ReentrantLock();

    private PinningDemo() {
    }

    public static void main(String[] args) throws Exception {
        long pinnedWithSync = countPinnedEvents(PinningDemo::synchronizedWorkload);
        long pinnedWithLock = countPinnedEvents(PinningDemo::reentrantWorkload);

        System.out.println("==== Virtual Thread Pinning Demo ====");
        System.out.printf("synchronized + block  → jdk.VirtualThreadPinned events: %d%n", pinnedWithSync);
        System.out.printf("ReentrantLock + block → jdk.VirtualThreadPinned events: %d%n", pinnedWithLock);
        System.out.println(pinnedWithSync > 0 && pinnedWithLock == 0
            ? "PASS: synchronized pin carrier; ReentrantLock unpin (đúng kỳ vọng)."
            : "CHECK: kết quả không như kỳ vọng — xem lại threshold / JDK build.");
    }

    /** Bật JFR ghi riêng event pinning (bỏ threshold để bắt mọi lần pin), chạy workload, đếm event. */
    private static long countPinnedEvents(Runnable workload) throws Exception {
        Path jfr = Files.createTempFile("pinning-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withoutThreshold();
            recording.start();
            workload.run();
            recording.stop();
            recording.dump(jfr);
        }
        try (RecordingFile rf = new RecordingFile(jfr)) {
            long count = 0;
            while (rf.hasMoreEvents()) {
                if (rf.readEvent().getEventType().getName().equals("jdk.VirtualThreadPinned")) {
                    count++;
                }
            }
            return count;
        } finally {
            Files.deleteIfExists(jfr);
        }
    }

    /** Block bên trong synchronized → carrier bị pin suốt 50ms. */
    private static void synchronizedWorkload() {
        runOnVirtualThreads(() -> {
            synchronized (MONITOR) {
                sleep(BLOCK_MILLIS);
            }
        });
    }

    /** Block khi giữ ReentrantLock → VT unmount, carrier free. */
    private static void reentrantWorkload() {
        runOnVirtualThreads(() -> {
            LOCK.lock();
            try {
                sleep(BLOCK_MILLIS);
            } finally {
                LOCK.unlock();
            }
        });
    }

    private static void runOnVirtualThreads(Runnable body) {
        CountDownLatch latch = new CountDownLatch(VTHREADS);
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < VTHREADS; i++) {
                exec.submit(() -> {
                    try {
                        body.run();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
