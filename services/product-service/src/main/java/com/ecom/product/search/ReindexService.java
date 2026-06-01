package com.ecom.product.search;

import com.ecom.product.domain.Product;
import com.ecom.product.domain.ProductStatus;
import com.ecom.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Day 22 — full reindex Postgres → ES. Ba lý do tồn tại:
 * <ol>
 *   <li><b>Initial load</b>: ES khởi động rỗng. Sync event chỉ cover product
 *       ĐỔI từ giờ trở đi — product cũ phải bulk-index 1 lần.</li>
 *   <li><b>Reconcile drift</b> (Day 25): dual-write có thể miss event (Kafka
 *       down lúc publish). Chạy reindex định kỳ (nightly) ép ES = Postgres.</li>
 *   <li><b>Benchmark</b>: nạp 1M doc để đo ES vs LIKE (performance/22).</li>
 * </ol>
 *
 * <p><b>Tại sao paging {@link Slice} thay vì {@code findAll()}?</b> 1M product
 * load 1 phát = OOM. Slice (không COUNT) cuốn từng batch {@code BATCH_SIZE},
 * convert → {@code saveAll} bulk vào ES. ES bulk API gộp nhiều doc/1 request →
 * throughput cao hơn index từng cái.
 *
 * <p>CHỈ index {@code ACTIVE} — DRAFT/ARCHIVED không nên search ra (đồng bộ
 * filter {@code status=ACTIVE} ở {@link ProductSearchService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    private static final int BATCH_SIZE = 1000;

    private final ProductRepository productRepository;
    private final ProductSearchRepository searchRepository;

    /**
     * @return số document đã index.
     */
    @Transactional(readOnly = true)
    public long reindexAll() {
        long indexed = 0;
        Pageable page = PageRequest.of(0, BATCH_SIZE);
        Slice<Product> slice;
        do {
            slice = productRepository.findByStatus(ProductStatus.ACTIVE, page);
            List<ProductDocument> batch = new ArrayList<>(slice.getNumberOfElements());
            for (Product p : slice.getContent()) {
                batch.add(toDocument(p));
            }
            if (!batch.isEmpty()) {
                searchRepository.saveAll(batch);
                indexed += batch.size();
            }
            log.info("Reindex progress: {} docs indexed (batch page {})", indexed, page.getPageNumber());
            page = slice.nextPageable();
        } while (slice.hasNext());
        log.info("Reindex complete: {} ACTIVE products → ES", indexed);
        return indexed;
    }

    private ProductDocument toDocument(Product p) {
        ProductDocument doc = new ProductDocument();
        doc.setId(p.getId().toString());
        doc.setSku(p.getSku());
        doc.setName(p.getName());
        doc.setDescription(p.getDescription());
        doc.setPrice(p.getPrice());
        doc.setCurrency(p.getCurrency());
        doc.setCategoryId(p.getCategory().getId().toString());
        doc.setCategorySlug(p.getCategory().getSlug());
        Object brand = p.getAttributes() == null ? null : p.getAttributes().get("brand");
        doc.setBrand(brand == null ? null : brand.toString());
        doc.setStatus(p.getStatus().name());
        doc.setAttributes(p.getAttributes());
        doc.setCreatedAt(p.getCreatedAt());
        return doc;
    }
}
