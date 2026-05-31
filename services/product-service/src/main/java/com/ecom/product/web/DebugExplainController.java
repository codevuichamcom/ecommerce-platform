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

import java.util.List;
import java.util.UUID;

/**
 * Day 16 — debug endpoint chạy {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)}
 * trên query search để demo before/after index tuning.
 *
 * <p><b>Gate</b>: chỉ load khi {@code app.debug.explain.enabled=true} — KHÔNG
 * bật trên prod (EXPLAIN ANALYZE thực sự chạy query → tốn DB cycle, và lộ
 * schema/cost info nếu accidentally exposed).
 *
 * <p><b>Auth</b>: KHÔNG yêu cầu role để dev test nhanh. Production phải set
 * flag false hoặc đặt sau internal-only network.
 *
 * @see "docs/performance/16-sql-explain-analyze.md"
 */
@RestController
@RequestMapping("/debug/explain")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.debug.explain.enabled", havingValue = "true")
public class DebugExplainController {

    @PersistenceContext
    private EntityManager em;

    /**
     * EXPLAIN cho query search hiện tại. Replicate WHERE clause của
     * {@link com.ecom.product.repository.ProductRepository#search} dạng native
     * SQL để dùng được {@code EXPLAIN}.
     *
     * <p>Tham số NULL → bỏ qua filter tương ứng (giống JPQL ở repo).
     *
     * @param q          keyword substring (nullable)
     * @param categoryId optional category filter (nullable)
     * @param status     optional status filter (nullable)
     * @return raw EXPLAIN output từng dòng, copy vào notebook để so before/after.
     */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public ApiResponse<List<String>> explainSearch(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String status) {

        // Dùng coalesce trick để giữ NULL-bypass semantics giống JPQL.
        // CAST cần thiết khi truyền NULL qua JDBC để Postgres biết type.
        String sql = """
                EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                SELECT p.id, p.name, p.price, p.status
                  FROM products p
                 WHERE (CAST(:kw AS text) IS NULL
                        OR LOWER(p.name) LIKE LOWER('%' || :kw || '%'))
                   AND (CAST(:cat AS uuid) IS NULL OR p.category_id = :cat)
                   AND (CAST(:st  AS text) IS NULL OR p.status = :st)
                 ORDER BY p.created_at DESC
                 LIMIT 20
                """;

        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery(sql)
                .setParameter("kw", q)
                .setParameter("cat", categoryId)
                .setParameter("st", status)
                .getResultList();
        return ApiResponse.ok(rows);
    }
}
