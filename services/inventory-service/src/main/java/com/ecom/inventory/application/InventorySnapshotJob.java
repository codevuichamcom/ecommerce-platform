package com.ecom.inventory.application;

import com.ecom.common.lock.DistributedLock;
import com.ecom.common.lock.LockHandle;
import com.ecom.inventory.domain.InventorySnapshotRepository;
import com.ecom.inventory.domain.StockRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily inventory snapshot — chạy trên MỌI instance nhưng chỉ leader (chiếm
 * được distributed lock) mới ghi.
 *
 * <p>Hai lớp phòng thủ:
 * <ol>
 *   <li><b>Distributed lock</b> ({@link DistributedLock}) — best-effort, giảm
 *       số instance cùng chạy xuống 1 trong điều kiện bình thường.</li>
 *   <li><b>Fencing token</b> ở DB (xem
 *       {@link InventorySnapshotRepository#upsertWithFence}) — lớp correctness
 *       thật, chặn stale writer khi lock bị split do GC pause.</li>
 * </ol>
 *
 * <p>Đây là hiện thực hoá scenario ở issues/19-redlock-correctness.md.
 */
@Component
public class InventorySnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(InventorySnapshotJob.class);
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final DistributedLock lock;
    private final StockRepository stockRepository;
    private final InventorySnapshotRepository snapshotRepository;
    private final String instanceId;

    public InventorySnapshotJob(DistributedLock lock,
                                StockRepository stockRepository,
                                InventorySnapshotRepository snapshotRepository) {
        this.lock = lock;
        this.stockRepository = stockRepository;
        this.snapshotRepository = snapshotRepository;
        // Định danh instance để truy vết "ai đã ghi" trong cột created_by_instance.
        String host = System.getenv("HOSTNAME");
        this.instanceId = (host == null || host.isBlank())
            ? "inv-" + UUID.randomUUID().toString().substring(0, 8)
            : host;
    }

    /** 02:00 mỗi ngày. */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledRun() {
        runOnce(LocalDate.now());
    }

    /**
     * Chạy snapshot cho 1 ngày. Tách khỏi {@link #scheduledRun()} để test gọi
     * được trực tiếp.
     *
     * @return số row DB bị ảnh hưởng (0 = không phải leader, HOẶC bị fence từ chối)
     */
    @Transactional
    public int runOnce(LocalDate date) {
        String key = "snapshot:" + date;
        Optional<LockHandle> maybeHandle = lock.tryAcquire(key, LOCK_TTL);
        if (maybeHandle.isEmpty()) {
            log.debug("Not leader for snapshot {} — bỏ qua (instance {})", date, instanceId);
            return 0;
        }

        LockHandle handle = maybeHandle.get();
        try {
            long totalSkus = stockRepository.count();
            long totalReserved = stockRepository.sumReserved();
            int rows = snapshotRepository.upsertWithFence(
                date, totalSkus, totalReserved, handle.fencingToken(), instanceId);

            if (rows == 0) {
                // Fence từ chối: instance này cầm token cũ (vd vừa qua GC pause).
                log.warn("STALE-WRITER blocked by fence: snapshot {} token {} instance {} — write rejected",
                    date, handle.fencingToken(), instanceId);
            } else {
                log.info("Snapshot {} written by {} (token {}): skus={} reserved={}",
                    date, instanceId, handle.fencingToken(), totalSkus, totalReserved);
            }
            return rows;
        } finally {
            lock.release(handle);
        }
    }
}
