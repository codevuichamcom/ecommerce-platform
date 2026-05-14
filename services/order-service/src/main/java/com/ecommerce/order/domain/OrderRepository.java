package com.ecommerce.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Port repository cho aggregate {@link Order}. Spring Data JPA generate
 * implementation. Cùng tư tưởng StockRepository ở Day 4 — không expose
 * generic finder, chỉ method có ngữ nghĩa business.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Idempotency check — tìm order theo (userId, idempotencyKey). */
    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
