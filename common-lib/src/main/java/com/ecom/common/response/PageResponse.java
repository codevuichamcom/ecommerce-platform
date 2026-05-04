package com.ecom.common.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Page envelope độc lập với Spring Data {@code Page}, an toàn cho contract
 * giữa các services và frontend (không leak Spring class qua API).
 *
 * <p>Tại sao không dùng thẳng {@code Page<T>}?
 * <ul>
 *   <li>{@code Page} có nhiều field thừa (pageable, sort) — JSON to và
 *       không cần thiết cho client.</li>
 *   <li>Khi đổi backend (vd Mongo, Elastic) sẽ phải đổi schema response —
 *       client vỡ.</li>
 * </ul>
 */
public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int size,
        int totalPages,
        boolean hasNext
) {

    public static <T> PageResponse<T> from(Page<T> p) {
        return new PageResponse<>(
                p.getContent(),
                p.getTotalElements(),
                p.getNumber(),
                p.getSize(),
                p.getTotalPages(),
                p.hasNext()
        );
    }

    public <R> PageResponse<R> map(java.util.function.Function<T, R> mapper) {
        return new PageResponse<>(
                items.stream().map(mapper).toList(),
                total, page, size, totalPages, hasNext
        );
    }
}
