package com.ecom.product.web;

import com.ecom.common.response.ApiResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 18 — debug endpoint so sánh <b>offset vs keyset</b> pagination ở CÙNG
 * độ sâu, chạy {@code EXPLAIN (ANALYZE, BUFFERS)} thật để có số liệu
 * before/after cho doc {@code performance/18-seek-pagination.md}.
 *
 * <p><b>Gate</b>: chỉ load khi {@code app.debug.explain.enabled=true} — giống
 * {@link DebugExplainController}. KHÔNG bật trên prod (chạy query thật + lộ
 * plan). Cần seed 1M (Day 16) để thấy chênh lệch rõ.
 *
 * <p>Cách đọc kết quả: nhìn dòng cuối "Execution Time" của 2 plan. Offset ở
 * page sâu sẽ có {@code Limit → ... rows removed by ... + Buffers} lớn (scan
 * + discard offset rows). Keyset có {@code Index Scan using idx_products_keyset}
 * + Buffers gần như hằng số bất kể độ sâu.
 *
 * @see "docs/performance/18-seek-pagination.md"
 */
@RestController
@RequestMapping("/debug/pagination")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.debug.explain.enabled", havingValue = "true")
public class DebugPaginationController {

    @PersistenceContext
    private EntityManager em;

    /**
     * Chạy 2 plan tại cùng vị trí logic (page thứ {@code offset/size}):
     * <ul>
     *   <li><b>offset</b>: {@code ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset}</li>
     *   <li><b>keyset</b>: seek tới đúng anchor ở {@code offset} rồi
     *       {@code WHERE (created_at,id) < (anchor) LIMIT :size}. Anchor lấy
     *       bằng 1 subquery {@code OFFSET :offset LIMIT 1} (chỉ để demo cùng
     *       điểm — production cursor đến từ response trước, không cần subquery này).</li>
     * </ul>
     *
     * @param offset độ sâu (số row bỏ qua). Thử 0, 100000, 980000 để thấy spike.
     * @param size   page size
     * @return map {offset: [...plan lines], keyset: [...plan lines]}
     */
    @GetMapping("/compare")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<String>>> compare(
            @RequestParam(defaultValue = "980000") long offset,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("offset", explainOffset(offset, size));
        out.put("keyset", explainKeyset(offset, size));
        return ApiResponse.ok(out);
    }

    private List<String> explainOffset(long offset, int size) {
        String sql = """
                EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                SELECT p.id, p.name, p.price, p.created_at
                  FROM products p
                 ORDER BY p.created_at DESC, p.id DESC
                 LIMIT :size OFFSET :offset
                """;
        return runExplain(sql, size, offset);
    }

    private List<String> explainKeyset(long offset, int size) {
        // Anchor = row ở vị trí offset (mô phỏng cursor mà response trước trả về).
        // Production KHÔNG có subquery này — cursor đến thẳng từ client.
        String sql = """
                EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                WITH anchor AS (
                    SELECT created_at, id
                      FROM products
                     ORDER BY created_at DESC, id DESC
                     OFFSET :offset LIMIT 1
                )
                SELECT p.id, p.name, p.price, p.created_at
                  FROM products p, anchor a
                 WHERE p.created_at < a.created_at
                    OR (p.created_at = a.created_at AND p.id < a.id)
                 ORDER BY p.created_at DESC, p.id DESC
                 LIMIT :size
                """;
        return runExplain(sql, size, offset);
    }

    @SuppressWarnings("unchecked")
    private List<String> runExplain(String sql, int size, long offset) {
        return em.createNativeQuery(sql)
                .setParameter("size", size)
                .setParameter("offset", offset)
                .getResultList();
    }
}
