package com.ecommerce.order.infrastructure.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository cho {@link OutboxEvent}.
 *
 * <p><b>fetchBatchForRelay</b>: SELECT FOR UPDATE SKIP LOCKED — Postgres
 * native pattern cho multi-instance relay race-free. Tham khảo
 * {@code lessons/13-outbox-pattern.md} §"Multi-instance relay".
 *
 * <p>{@code jakarta.persistence.lock.timeout=0}: nếu row đang lock bởi
 * instance khác → SKIP ngay (không wait). Khác với default LockModeType
 * gồm pessimistic wait có thể block relay tick.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2") // SKIP LOCKED
    })
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.status = com.ecommerce.order.infrastructure.outbox.OutboxStatus.PENDING
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> fetchBatchForRelay(Pageable pageable);

    /**
     * Đếm PENDING age — feed SLI alert "outbox lag" (Day 20 wire Micrometer).
     * Trả oldest createdAt; null nếu queue empty.
     */
    @Query("""
            SELECT MIN(e.createdAt) FROM OutboxEvent e
            WHERE e.status = com.ecommerce.order.infrastructure.outbox.OutboxStatus.PENDING
            """)
    Instant findOldestPendingCreatedAt();
}
