package com.ecom.inventory;

import com.ecom.common.exception.BusinessException;
import com.ecom.inventory.application.InventoryService;
import com.ecom.inventory.domain.InsufficientStockException;
import com.ecom.inventory.domain.Stock;
import com.ecom.inventory.domain.StockRepository;
import com.ecom.inventory.support.PostgresTestcontainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test — deliverable chính của Day 4.
 *
 * <p>Setup: 1 SKU, quantity=50. Bắn 100 thread cùng reserve(qty=1).
 * Expected:
 * <ul>
 *   <li>50 thread thành công.</li>
 *   <li>50 thread fail với InsufficientStockException (KHÔNG phải
 *       OptimisticLockException — retry phải tự handle).</li>
 *   <li>Stock cuối: quantity=50, reserved=50, available=0.</li>
 *   <li>NO oversell — invariant {@code reserved ≤ quantity}.</li>
 * </ul>
 *
 * <p>Skip mặc định trên local Windows (Docker Desktop 29.x compat). Run bằng
 * {@code RUN_INVENTORY_INTEGRATION_TESTS=true}.
 */
@SpringBootTest
@Import(PostgresTestcontainerConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_INVENTORY_INTEGRATION_TESTS", matches = "true")
class InventoryConcurrencyIT {

    private static final String SKU = "SKU-CONCURRENCY-TEST";
    private static final int INITIAL_QTY = 50;
    private static final int THREADS = 100;

    @Autowired InventoryService inventoryService;
    @Autowired StockRepository stockRepository;

    @BeforeEach
    void setup() {
        stockRepository.deleteAll();
        stockRepository.save(Stock.create(SKU, INITIAL_QTY));
    }

    @Test
    void hundredThreads_reserveOne_noOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try {
            CompletableFuture<?>[] futures = new CompletableFuture[THREADS];
            for (int i = 0; i < THREADS; i++) {
                futures[i] = CompletableFuture.runAsync(() -> {
                    try {
                        inventoryService.reserve(SKU, 1);
                        success.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        insufficient.incrementAndGet();
                    } catch (BusinessException e) {
                        // Retry-exhausted CONFLICT — vẫn count là other failure.
                        other.incrementAndGet();
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                }, pool);
            }
            CompletableFuture.allOf(futures).get(Duration.ofMinutes(1).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdown();
        }

        Stock finalStock = stockRepository.findById(SKU).orElseThrow();

        // KHÔNG OVERSELL — invariant chính của Day 4.
        assertThat(finalStock.getReserved()).isEqualTo(INITIAL_QTY);
        assertThat(finalStock.getQuantity()).isEqualTo(INITIAL_QTY);
        assertThat(finalStock.available()).isZero();

        // Đúng INITIAL_QTY thread thành công, phần còn lại fail nghiệp vụ.
        assertThat(success.get()).isEqualTo(INITIAL_QTY);
        assertThat(success.get() + insufficient.get() + other.get()).isEqualTo(THREADS);
        // Other (retry exhausted / unknown) phải nhỏ — nếu lớn signal pool/retry config sai.
        assertThat(other.get()).isLessThan(THREADS / 10);
    }
}
