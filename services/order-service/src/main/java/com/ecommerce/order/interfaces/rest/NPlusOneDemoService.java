package com.ecommerce.order.interfaces.rest;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Day 17 — đo N+1 thật bằng Hibernate {@link Statistics}. Bean chỉ tồn tại
 * khi {@code app.debug.explain.enabled=true} (reuse flag debug của Day 16)
 * — production KHÔNG load. Mục đích: chạy 4 nấc fetch side-by-side và đếm số
 * JDBC prepared statement thực sự bắn xuống DB.
 *
 * <p>Vì sao chạy trong CÙNG 1 transaction nhưng {@code em.clear()} giữa mỗi
 * nấc? Để mỗi nấc có persistence context sạch (L1 cache trống) → đếm honest,
 * không bị nấc trước "hâm nóng" cache làm nấc sau ra 0 query.
 *
 * @see OrderRepository
 */
@Service
@ConditionalOnProperty(name = "app.debug.explain.enabled", havingValue = "true")
@RequiredArgsConstructor
public class NPlusOneDemoService {

    private final OrderRepository orderRepository;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public Map<String, Object> measure(UUID userId, int size) {
        Statistics stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        stats.setStatisticsEnabled(true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId.toString());
        result.put("pageSize", size);

        // Nấc 0 — N+1: 1 query order + N query items (EAGER collection).
        long n0 = countQueries(stats, () -> {
            Page<Order> page = orderRepository.findByUserId(userId, PageRequest.of(0, size));
            touch(page.getContent());
        });
        result.put("0_derived_eager_N_plus_1", n0 + " queries (≈ 1 + N items)");

        // Nấc 1 — @EntityGraph: 1 query JOIN FETCH, NHƯNG pagination in-memory.
        long n1 = countQueries(stats, () -> {
            Page<Order> page = orderRepository.findWithItemsByUserId(userId, PageRequest.of(0, size));
            touch(page.getContent());
        });
        result.put("1_entitygraph", n1 + " queries (+1 count) — xem log HHH000104 in-memory pagination");

        // Nấc 2 — JOIN FETCH viết tay: 1 query, không phân trang.
        long n2 = countQueries(stats, () -> touch(orderRepository.findAllWithItemsByUserId(userId)));
        result.put("2_join_fetch", n2 + " queries (no pagination)");

        // Nấc 3 — projection: 1 query select + 1 count, KHÔNG load entity.
        long n3 = countQueries(stats, () ->
                orderRepository.findSummariesByUserId(userId, PageRequest.of(0, size)).getContent());
        result.put("3_projection", n3 + " queries (production list path)");

        result.put("note", "Đọc log SQL: nấc 0 = nhiều SELECT order_items; nấc 1 = 1 join + WARN HHH000104; "
                + "nấc 3 = select scalar + count, không có SELECT items.");
        return result;
    }

    /** Đếm prepared statement bắn ra trong lúc chạy block, sau khi reset L1 + stats. */
    private long countQueries(Statistics stats, Runnable block) {
        em.clear();
        stats.clear();
        block.run();
        return stats.getPrepareStatementCount();
    }

    /** Ép khởi tạo collection items để N+1 (nếu có) lộ ra trong lúc đo. */
    private void touch(List<Order> orders) {
        orders.forEach(o -> o.getItems().size());
    }
}
