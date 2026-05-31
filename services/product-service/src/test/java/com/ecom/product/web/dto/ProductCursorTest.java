package com.ecom.product.web.dto;

import com.ecom.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 18 — unit test cho cursor codec. KHÔNG cần DB: encode/decode là pure.
 * Trọng tâm: round-trip phải lossless tới microsecond (precision của
 * Postgres TIMESTAMPTZ) + token rác phải fail-safe thành BusinessException
 * (→ 400), KHÔNG để bubble lên 500.
 */
class ProductCursorTest {

    @Test
    void roundTrip_preservesMicrosecondPrecision() {
        // Instant có nanosecond; Postgres chỉ giữ micro → cursor phải khớp ở
        // mức micro. Dùng giá trị có phần micro khác 0 để bắt lỗi cắt sai.
        Instant at = Instant.parse("2026-05-31T10:15:30.123456Z");
        UUID id = UUID.randomUUID();

        ProductCursor decoded = ProductCursor.decode(new ProductCursor(at, id).encode());

        assertThat(decoded.id()).isEqualTo(id);
        assertThat(decoded.createdAt()).isEqualTo(at);
    }

    @Test
    void encode_isOpaque_doesNotLeakRawId() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String token = new ProductCursor(Instant.now(), id).encode();

        // Token base64 KHÔNG được chứa UUID thô — client không enumerate được.
        assertThat(token).doesNotContain(id.toString());
    }

    @Test
    void decode_garbageToken_throwsBadRequest_not500() {
        assertThatThrownBy(() -> ProductCursor.decode("!!!not-base64!!!"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void decode_validBase64ButWrongShape_throwsBadRequest() {
        // "aGVsbG8" = base64 của "hello" — decode OK nhưng thiếu separator ':'.
        assertThatThrownBy(() -> ProductCursor.decode("aGVsbG8"))
                .isInstanceOf(BusinessException.class);
    }
}
