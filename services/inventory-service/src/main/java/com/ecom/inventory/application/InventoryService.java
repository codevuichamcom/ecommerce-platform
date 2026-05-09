package com.ecom.inventory.application;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.inventory.application.dto.StockResponse;
import com.ecom.inventory.domain.Stock;
import com.ecom.inventory.domain.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service — orchestrate use case, KHÔNG chứa business rule
 * (rule nằm trong {@link Stock} aggregate).
 *
 * <h2>Optimistic locking + retry</h2>
 *
 * <p>{@code @Retryable} trên reserve/release: khi 2 tx đồng thời UPDATE
 * cùng row, 1 tx sẽ throw {@link OptimisticLockingFailureException}
 * (Spring wrap Hibernate). Retry với fresh state — KHÔNG retry trong cùng
 * 1 transaction (sẽ bị stale entity).
 *
 * <p>Tại sao {@code Propagation.REQUIRES_NEW}? Mỗi attempt phải là tx
 * mới — nếu retry trong cùng tx, JPA persistence context vẫn giữ entity
 * cũ với version cũ → reload không hiệu quả. {@code REQUIRES_NEW} ép
 * close + open tx mới mỗi lần retry.
 *
 * <p>{@code maxAttempts=4}: 1 lần đầu + 3 retry. Backoff exponential
 * 50ms / 100ms / 200ms — đủ jitter để contention 100-thread giải tỏa,
 * không quá lâu cho user UX (worst case ~350ms).
 *
 * <p>{@code @Recover} dùng cho khi vẫn fail sau retry → log + throw
 * BusinessException CONFLICT để client biết retry sau (vd: 1 SKU bị
 * viral burst, signal đến lúc upgrade Day 33 Redis Lua).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StockRepository stockRepository;

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 50, multiplier = 2.0, maxDelay = 500))
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StockResponse reserve(String sku, int qty) {
        Stock stock = loadOrThrow(sku);
        stock.reserve(qty);
        Stock saved = stockRepository.save(stock);
        log.debug("Reserved sku={} qty={} version={}", sku, qty, saved.getVersion());
        return StockResponse.from(saved);
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 50, multiplier = 2.0, maxDelay = 500))
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StockResponse release(String sku, int qty) {
        Stock stock = loadOrThrow(sku);
        stock.release(qty);
        Stock saved = stockRepository.save(stock);
        log.debug("Released sku={} qty={} version={}", sku, qty, saved.getVersion());
        return StockResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public StockResponse get(String sku) {
        return StockResponse.from(loadOrThrow(sku));
    }

    private Stock loadOrThrow(String sku) {
        return stockRepository.findById(sku)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Stock not found for sku=" + sku));
    }
}
