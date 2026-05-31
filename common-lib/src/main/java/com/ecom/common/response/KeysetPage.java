package com.ecom.common.response;

import java.util.List;
import java.util.function.Function;

/**
 * Envelope cho <b>keyset (seek) pagination</b> — Day 18.
 *
 * <p>Khác {@link PageResponse} (offset) ở 2 điểm cốt lõi:
 * <ul>
 *   <li><b>KHÔNG có {@code total} / {@code totalPages}</b>. Keyset không
 *       chạy {@code COUNT(*)} — đó chính là lý do nó nhanh O(N) bất kể page
 *       sâu. Muốn hiển thị "≈ 1.2M kết quả" thì lấy approximate count
 *       ({@code pg_class.reltuples}) ở tầng riêng, KHÔNG nhét vào read path.</li>
 *   <li>Điều hướng bằng <b>{@code nextCursor}</b> (opaque token) thay vì số
 *       page. Client gửi lại token này ở request kế → next/prev, không
 *       jump-to-page. UX = infinite scroll.</li>
 * </ul>
 *
 * <p>{@code nextCursor == null} ⇔ {@code hasNext == false} ⇔ hết data.
 * Token là opaque (base64) — client KHÔNG được parse, chỉ echo lại. Giữ
 * như vậy để sau đổi cursor structure (thêm sort field) không vỡ contract.
 *
 * @param items      page hiện tại (đã map sang DTO, không leak entity)
 * @param nextCursor token để lấy page kế; {@code null} nếu đã hết
 * @param hasNext    còn data phía sau không
 * @param size       số phần tử mỗi page (page size yêu cầu, đã cap)
 */
public record KeysetPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasNext,
        int size
) {

    public static <T> KeysetPage<T> of(List<T> items, String nextCursor, int size) {
        return new KeysetPage<>(items, nextCursor, nextCursor != null, size);
    }

    /** Map item type, giữ nguyên cursor + metadata (DTO mapping pattern). */
    public <R> KeysetPage<R> map(Function<T, R> mapper) {
        return new KeysetPage<>(
                items.stream().map(mapper).toList(),
                nextCursor, hasNext, size
        );
    }
}
