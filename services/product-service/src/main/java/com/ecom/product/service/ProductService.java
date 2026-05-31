package com.ecom.product.service;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.response.KeysetPage;
import com.ecom.common.response.PageResponse;
import com.ecom.product.domain.Category;
import com.ecom.product.domain.Product;
import com.ecom.product.domain.ProductStatus;
import com.ecom.product.mapper.ProductMapper;
import com.ecom.product.repository.CategoryRepository;
import com.ecom.product.repository.ProductRepository;
import com.ecom.product.web.dto.ProductCreateRequest;
import com.ecom.product.web.dto.ProductCursor;
import com.ecom.product.web.dto.ProductResponse;
import com.ecom.product.web.dto.ProductSnapshotResponse;
import com.ecom.product.web.dto.ProductUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.ecom.product.config.cache.CacheConfig.CACHE_PRODUCT_BY_ID;
import static com.ecom.product.config.cache.CacheConfig.CACHE_PRODUCT_BY_SLUG;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Catalog use case. Layered (không DDD): không có aggregate root, không
 * domain event. Đủ phức tạp business — nâng DDD = over-engineer.
 *
 * <p>{@code @Transactional(readOnly=true)} ở class — write method override.
 * readOnly=true → Hibernate skip dirty check, Postgres dùng read-only TX
 * mode → có thể dispatch sang replica sau (Day 16+).
 *
 * <p>Map sang DTO TRONG transaction để LAZY association (`category`)
 * còn session — nếu return entity ra controller sẽ
 * {@code LazyInitializationException} (xem issue 03).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    /**
     * Trần độ sâu offset. page 500 × size 100 = scan tối đa 50K rows — ngưỡng
     * latency còn chấp nhận. Sâu hơn ép client chuyển keyset (infinite scroll).
     */
    private static final int MAX_OFFSET_PAGE = 500;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;
    private final CacheManager cacheManager;

    /**
     * Cached read — qua 2-tier (Caffeine L1 + Redis L2) + XFetch stampede
     * protection. {@code unless="#result == null"} không cần vì
     * {@code loadOrThrow} throw thay vì trả null. Spring Cache skip cache
     * khi method throw exception → 404 sẽ không poison cache.
     */
    @Cacheable(value = CACHE_PRODUCT_BY_ID, key = "#id")
    public ProductResponse get(UUID id) {
        return productMapper.toResponse(loadOrThrow(id));
    }

    /**
     * Day 8 — lightweight snapshot cho order-service. Order capture
     * price/name/currency tại checkout time vào order_items để tránh
     * price drift khi admin đổi giá.
     *
     * <p>KHÔNG fetch category/attributes — tiết kiệm payload khi
     * order có N item, gọi N lần snapshot.
     */
    public ProductSnapshotResponse getSnapshot(String sku) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found by sku: " + sku));
        return new ProductSnapshotResponse(
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getCurrency()
        );
    }

    @Cacheable(value = CACHE_PRODUCT_BY_SLUG, key = "#slug")
    public ProductResponse getBySlug(String slug) {
        Product product = productRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + slug));
        return productMapper.toResponse(product);
    }

    /**
     * Search + pagination offset-based. Day 18 sẽ migrate keyset (seek)
     * pagination cho large dataset; Day 22 migrate ES.
     *
     * <p>Sort whitelist: chỉ cho `createdAt` / `price` để chống user inject
     * sort field tùy ý (vd `password_hash` từ join table khác — không
     * apply ở đây nhưng good habit).
     */
    public PageResponse<ProductResponse> search(String keyword,
                                                UUID categoryId,
                                                ProductStatus status,
                                                int page,
                                                int size,
                                                String sortBy,
                                                Sort.Direction direction) {
        // Day 18 — hard cap độ sâu offset. Deep page (vd 49000) buộc Postgres
        // scan + discard hàng trăm K rows → p99 spike + nguy cơ enumerate cả
        // catalog. Vượt ngưỡng → 400 + hướng client sang keyset endpoint.
        if (page > MAX_OFFSET_PAGE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Offset pagination giới hạn page ≤ " + MAX_OFFSET_PAGE
                            + ". Dùng GET /products/keyset cho deep scroll.");
        }
        String safeSort = switch (sortBy == null ? "createdAt" : sortBy) {
            case "price" -> "price";
            case "name"  -> "name";
            default       -> "createdAt";
        };
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 100),  // hard cap 100 — chống enumerate cả DB
                Sort.by(direction == null ? Sort.Direction.DESC : direction, safeSort));

        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<Product> result = productRepository.search(kw, categoryId, status, pageable);
        return PageResponse.from(result).map(productMapper::toResponse);
    }

    /**
     * Day 18 — <b>keyset (seek) pagination</b> cho large dataset (1M+ rows).
     *
     * <p>Tại sao tồn tại song song với {@link #search} (offset)? Offset chậm
     * tuyến tính theo độ sâu: {@code OFFSET 980000} buộc Postgres scan + discard
     * 980K rows trước khi trả 20 → p99 nhảy lên hàng giây. Keyset seek thẳng
     * tới vị trí cursor qua index {@code (created_at DESC, id DESC)} → cost
     * gần như hằng số bất kể page sâu. Đổi lại: mất jump-to-page (chỉ next),
     * mất {@code total} (không COUNT). Mobile infinite-scroll xài keyset, admin
     * cần "page 5/100" giữ offset.
     *
     * <p>Sort cố định {@code created_at DESC, id DESC} — KHÔNG cho sort động ở
     * đây. Mỗi sort khác = 1 composite index + 1 cursor encode khác (xem
     * lesson 03 §Cạm bẫy #4). Day 22 đẩy multi-sort/relevance sang ES.
     *
     * <p>Cơ chế {@code hasNext} không cần COUNT: fetch {@code size + 1} row.
     * Nếu DB trả về dư 1 ⇒ còn page sau → cắt row thừa, build cursor từ row
     * <i>cuối cùng được giữ lại</i> (row thứ {@code size}).
     *
     * @param cursorToken opaque token từ response trước; {@code null}/blank = trang đầu
     */
    public KeysetPage<ProductResponse> searchKeyset(String keyword,
                                                    UUID categoryId,
                                                    ProductStatus status,
                                                    String cursorToken,
                                                    int size) {
        int safeSize = Math.min(Math.max(1, size), 100);  // hard cap 100 — đồng bộ với offset
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        ProductCursor cursor = (cursorToken == null || cursorToken.isBlank())
                ? null
                : ProductCursor.decode(cursorToken);  // token rác → 400 (xem ProductCursor#decode)

        // Fetch size+1 để dò hasNext mà không COUNT.
        List<Product> rows = productRepository.searchKeyset(
                kw, categoryId, status,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(),
                Limit.of(safeSize + 1));

        boolean hasNext = rows.size() > safeSize;
        List<Product> pageRows = hasNext ? rows.subList(0, safeSize) : rows;

        // nextCursor build từ row CUỐI của page (sau khi cắt row thừa). Nếu
        // không còn page sau → null (KeysetPage.of suy ra hasNext=false).
        String nextCursor = null;
        if (hasNext) {
            Product last = pageRows.get(pageRows.size() - 1);
            nextCursor = new ProductCursor(last.getCreatedAt(), last.getId()).encode();
        }

        List<ProductResponse> items = pageRows.stream().map(productMapper::toResponse).toList();
        return KeysetPage.of(items, nextCursor, safeSize);
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest req) {
        if (productRepository.existsBySku(req.sku())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product SKU already exists");
        }
        if (productRepository.existsBySlug(req.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product slug already exists");
        }
        Category category = loadCategoryOrThrow(req.categoryId());

        Product product = Product.builder()
                .id(UUID.randomUUID())
                .sku(req.sku())
                .name(req.name())
                .slug(req.slug())
                .description(req.description())
                .price(req.price())
                .currency(req.currency().toUpperCase())
                .category(category)
                .status(req.status())
                .attributes(req.attributes() == null ? new HashMap<>() : new HashMap<>(req.attributes()))
                .build();

        return productMapper.toResponse(productRepository.save(product));
    }

    /**
     * Update path — invalidate cả 2 cache name (byId + bySlug). Slug có thể
     * đổi → phải evict BOTH old slug và new slug. Old slug evict bằng
     * {@code product.getSlug()} (đọc TRƯỚC khi setSlug). New slug evict gián
     * tiếp qua key="#req.slug()".
     *
     * <p>{@code @Caching} composite vì 1 method update touch 2 cache name.
     * Spring không support multiple cache name trong 1 @CacheEvict với keys
     * khác nhau.
     *
     * <p>{@code beforeInvocation=false} (default): chỉ evict NẾU method commit
     * thành công. Lỗi (vd validation) → cache giữ nguyên, không invalidate
     * nhầm.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CACHE_PRODUCT_BY_ID, key = "#id"),
            @CacheEvict(value = CACHE_PRODUCT_BY_SLUG, key = "#req.slug()"),
            // Old slug evict — đọc trong method body qua programmatic API nếu
            // slug đổi. Spring @CacheEvict không evaluate SpEL TRƯỚC method
            // call (chỉ AFTER + access tới #result hoặc args), nên không thể
            // viết key="#product.oldSlug". Method body sẽ xử lý manually qua
            // CacheManager nếu detect slug đổi.
    })
    public ProductResponse update(UUID id, ProductUpdateRequest req) {
        Product product = loadOrThrow(id);
        String oldSlug = product.getSlug();

        if (!oldSlug.equals(req.slug()) && productRepository.existsBySlug(req.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product slug already exists");
        }
        // Slug đổi → evict cache key của OLD slug. @CacheEvict declarative
        // chỉ evict NEW slug (key="#req.slug()") — phải manual evict old.
        if (!oldSlug.equals(req.slug())) {
            Cache slugCache = cacheManager.getCache(CACHE_PRODUCT_BY_SLUG);
            if (slugCache != null) slugCache.evict(oldSlug);
        }
        Category category = product.getCategory().getId().equals(req.categoryId())
                ? product.getCategory()
                : loadCategoryOrThrow(req.categoryId());

        product.setName(req.name());
        product.setSlug(req.slug());
        product.setDescription(req.description());
        product.setPrice(req.price());
        product.setCurrency(req.currency().toUpperCase());
        product.setCategory(category);
        product.setStatus(req.status());
        product.setAttributes(req.attributes() == null ? new HashMap<>() : new HashMap<>(req.attributes()));

        return productMapper.toResponse(product);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CACHE_PRODUCT_BY_ID, key = "#id"),
            // Archive: slug vẫn còn — evict cache bySlug để query tiếp theo
            // không trả product status=ACTIVE (cache stale).
            @CacheEvict(value = CACHE_PRODUCT_BY_SLUG, allEntries = true,
                    condition = "true")
    })
    public void archive(UUID id) {
        // Day 3 chưa hard-delete — luôn ARCHIVE để giữ historical reference
        // (order/invoice cũ còn point tới SKU này).
        //
        // Cache invalidation: byId key=#id, bySlug allEntries=true vì không
        // biết slug ở đây mà không query lại (đỡ 1 round trip). Trade-off:
        // archive 1 product → flush toàn bộ slug cache. Acceptable vì archive
        // là low-frequency operation (≪ read).
        Product product = loadOrThrow(id);
        product.setStatus(ProductStatus.ARCHIVED);
    }

    private Product loadOrThrow(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + id));
    }

    private Category loadCategoryOrThrow(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Category not found: " + id));
    }
}
