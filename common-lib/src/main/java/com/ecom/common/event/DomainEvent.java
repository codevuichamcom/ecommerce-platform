package com.ecom.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Base contract cho event publish lên Kafka. Mọi event payload phải có:
 * <ul>
 *   <li>{@code eventId} — dedup key cho consumer (Day 10 idempotent handler dùng cái này).</li>
 *   <li>{@code occurredAt} — domain time (KHÔNG phải publish time — chú ý
 *       khi event re-publish từ outbox Day 13 thì cái này GIỮ NGUYÊN).</li>
 *   <li>{@code eventType} — discriminator cho generic consumer.</li>
 *   <li>{@code eventVersion} — schema version. Default v1; breaking change → v2 topic mới.</li>
 * </ul>
 *
 * <p>Tại sao interface với 4 method thay vì abstract class với 4 field?
 * Vì payload là Java record — record không inherit field từ class được,
 * chỉ implement interface được. Interface + accessor method là idiom chuẩn
 * cho cross-cutting metadata trên record.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    String eventType();

    default int eventVersion() {
        return 1;
    }
}
