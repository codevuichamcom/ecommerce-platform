package com.ecommerce.order;

import com.ecommerce.order.application.dto.OrderSummaryView;
import com.ecommerce.order.domain.Address;
import com.ecommerce.order.domain.Money;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.support.PostgresTestcontainerConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 17 — chứng minh N+1 bằng số query THẬT (Hibernate {@link Statistics}),
 * không phải bằng cảm giác. Skip mặc định trên local (cần Docker); run bằng
 * {@code RUN_ORDER_INTEGRATION_TESTS=true}.
 *
 * <p>Dùng {@code @DataJpaTest} (JPA slice) — không load web/security/kafka,
 * chạy nhanh, đủ để đo fetch behavior. Flyway chạy migration thật V1..V3
 * trên Postgres container, Hibernate {@code validate}.
 *
 * <p>Seed {@value #ORDER_COUNT} order × {@value #ITEMS_PER_ORDER} item. Kỳ vọng:
 * <ul>
 *   <li>nấc 0 derived (EAGER) → ≥ 1 + N query (N+1 lộ rõ).</li>
 *   <li>nấc 2 JOIN FETCH → đúng 1 query.</li>
 *   <li>nấc 3 projection → ≤ 2 query (select + count), itemCount đúng.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainerConfig.class, OrderNPlusOneIntegrationTest.AuditingConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true"
})
@EnabledIfEnvironmentVariable(named = "RUN_ORDER_INTEGRATION_TESTS", matches = "true")
class OrderNPlusOneIntegrationTest {

    /**
     * {@code @DataJpaTest} slice KHÔNG load common-lib auto-config, nên
     * {@code @EnableJpaAuditing} không active → {@code @CreatedDate created_at}
     * (NOT NULL ở BaseEntity) bị null khi insert. Bật auditing thủ công ở đây.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class AuditingConfig {
    }

    private static final int ORDER_COUNT = 5;
    private static final int ITEMS_PER_ORDER = 3;
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired OrderRepository orderRepository;
    @Autowired EntityManagerFactory emf;
    @PersistenceContext EntityManager em;

    private Statistics stats;

    @BeforeEach
    void seed() {
        Address addr = new Address("Tonny", "0900000000", "1 Le Loi", "HCMC", "VN");
        for (int i = 0; i < ORDER_COUNT; i++) {
            Order order = Order.create(USER_ID, addr, "VND", null);
            for (int j = 0; j < ITEMS_PER_ORDER; j++) {
                order.addItem("SKU-" + i + "-" + j, "Product " + j, j + 1, new Money(10_000L, "VND"));
            }
            orderRepository.save(order);
        }
        em.flush();
        em.clear(); // detach hết — query sau phải vào DB thật, không hit L1.

        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
    }

    @Test
    void derivedQuery_eagerCollection_triggersNPlusOne() {
        em.clear();
        stats.clear();

        Page<Order> page = orderRepository.findByUserId(USER_ID, PageRequest.of(0, 10));
        page.getContent().forEach(o -> o.getItems().size()); // ép init items

        assertThat(page.getContent()).hasSize(ORDER_COUNT);
        // 1 query orders + N query items (+1 count cho Page) → ≥ 1 + N.
        assertThat(stats.getPrepareStatementCount())
                .as("EAGER derived query phải bắn ≥ 1 + N query (N+1)")
                .isGreaterThanOrEqualTo(1 + ORDER_COUNT);
    }

    @Test
    void joinFetch_collapsesToSingleQuery() {
        em.clear();
        stats.clear();

        List<Order> orders = orderRepository.findAllWithItemsByUserId(USER_ID);
        orders.forEach(o -> o.getItems().size());

        assertThat(orders).hasSize(ORDER_COUNT);
        assertThat(orders.getFirst().getItems()).hasSize(ITEMS_PER_ORDER);
        assertThat(stats.getPrepareStatementCount())
                .as("JOIN FETCH phải gom về đúng 1 query")
                .isEqualTo(1);
    }

    @Test
    void projection_isLeanAndCountsItemsCorrectly() {
        em.clear();
        stats.clear();

        Page<OrderSummaryView> page = orderRepository.findSummariesByUserId(USER_ID, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(ORDER_COUNT);
        assertThat(page.getContent()).allSatisfy(v -> {
            assertThat(v.itemCount()).isEqualTo(ITEMS_PER_ORDER);
            assertThat(v.currency()).isEqualTo("VND");
        });
        // select projection + count query cho Page = 2. KHÔNG có SELECT items.
        assertThat(stats.getPrepareStatementCount())
                .as("projection chỉ select scalar + count, không load entity/items")
                .isLessThanOrEqualTo(2);
    }
}
