package com.ecom.product.service;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.response.PageResponse;
import com.ecom.product.domain.Category;
import com.ecom.product.domain.Product;
import com.ecom.product.domain.ProductStatus;
import com.ecom.product.mapper.ProductMapper;
import com.ecom.product.repository.CategoryRepository;
import com.ecom.product.repository.ProductRepository;
import com.ecom.product.web.dto.ProductCreateRequest;
import com.ecom.product.web.dto.ProductResponse;
import com.ecom.product.web.dto.ProductSnapshotResponse;
import com.ecom.product.web.dto.ProductUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;

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

    @Transactional
    public ProductResponse update(UUID id, ProductUpdateRequest req) {
        Product product = loadOrThrow(id);

        if (!product.getSlug().equals(req.slug()) && productRepository.existsBySlug(req.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product slug already exists");
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
    public void archive(UUID id) {
        // Day 3 chưa hard-delete — luôn ARCHIVE để giữ historical reference
        // (order/invoice cũ còn point tới SKU này).
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
