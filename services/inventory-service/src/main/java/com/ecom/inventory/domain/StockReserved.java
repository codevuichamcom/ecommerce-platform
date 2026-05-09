package com.ecom.inventory.domain;

import java.time.Instant;

/**
 * Domain event — phát ra khi {@link Stock#reserve(int)} thành công.
 *
 * <p>Day 4: chỉ raise qua {@code AbstractAggregateRoot.registerEvent}
 * (Spring ApplicationEvent in-process). Day 9 sẽ wire
 * {@code @TransactionalEventListener(AFTER_COMMIT)} → publish Kafka topic
 * {@code inventory.stock-reserved} qua outbox table.
 */
public record StockReserved(String sku, int qty, Instant occurredAt) {}
