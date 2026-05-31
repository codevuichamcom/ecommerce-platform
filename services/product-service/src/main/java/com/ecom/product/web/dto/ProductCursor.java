package com.ecom.product.web.dto;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Cursor cho keyset pagination của list product — Day 18.
 *
 * <p>Sort chính là {@code created_at DESC}. Nhưng {@code created_at} KHÔNG
 * unique (nhiều product seed cùng millisecond) → phải tie-break bằng
 * {@code id} để tránh skip/duplicate row ở ranh giới page. Vì vậy cursor
 * mang <b>cả 2</b> giá trị {@code (createdAt, id)} — đúng bằng composite
 * sort key. Đây là cạm bẫy #1 của keyset (xem lesson 03 §Cạm bẫy).
 *
 * <p><b>Opaque token</b>: encode {@code "<epochMicros>:<uuid>"} qua base64
 * URL-safe. Client KHÔNG được parse — chỉ echo lại token ở request kế. Lý do:
 * <ul>
 *   <li>Không leak internal id thô / không gợi ý có thể enumerate.</li>
 *   <li>Đổi cursor structure sau (thêm sort field) không vỡ contract — token
 *       cũ chỉ cần versioning, client không phụ thuộc format.</li>
 * </ul>
 * KHÔNG ký (HMAC) ở đây vì list product là public read, tamper cursor chỉ
 * khiến user thấy data khác — không phải lỗ hổng. Nếu cursor scope theo user
 * (vd "đơn của tôi") thì phải ký để chống IDOR — xem note ở order-service.
 *
 * <p>Dùng epoch <b>micros</b> (không millis) vì Postgres {@code TIMESTAMPTZ}
 * lưu microsecond precision. Encode millis → mất 3 chữ số → cursor không khớp
 * chính xác giá trị DB → có thể lặp/skip row biên. {@link Instant} của Java
 * có nanosecond nhưng JDBC/Postgres chỉ giữ tới micro → cắt về micro cho khớp.
 */
public record ProductCursor(Instant createdAt, UUID id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** Encode sang opaque token để trả về client. */
    public String encode() {
        long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
        String raw = micros + ":" + id;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode token client gửi lên. Token rác / sai format → 400 BAD_REQUEST,
     * KHÔNG để bubble lên thành 500. Đây là input từ client — phải defensive.
     */
    public static ProductCursor decode(String token) {
        try {
            String raw = new String(DECODER.decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0) {
                throw new IllegalArgumentException("missing separator");
            }
            long micros = Long.parseLong(raw.substring(0, sep));
            UUID id = UUID.fromString(raw.substring(sep + 1));
            Instant createdAt = Instant.ofEpochSecond(
                    Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L);
            return new ProductCursor(createdAt, id);
        } catch (IllegalArgumentException e) {
            // NumberFormatException / IllegalArgumentException (base64, UUID) đều là con của IAE.
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cursor không hợp lệ");
        }
    }
}
