package com.ecom.product.service;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.product.domain.Category;
import com.ecom.product.mapper.CategoryMapper;
import com.ecom.product.repository.CategoryRepository;
import com.ecom.product.web.dto.CategoryRequest;
import com.ecom.product.web.dto.CategoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public List<CategoryResponse> list() {
        return categoryRepository.findAll().stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    public CategoryResponse get(UUID id) {
        return categoryMapper.toResponse(loadOrThrow(id));
    }

    @Transactional
    public CategoryResponse create(CategoryRequest req) {
        if (categoryRepository.existsBySlug(req.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Category slug already exists");
        }
        Category parent = req.parentId() == null ? null : loadOrThrow(req.parentId());
        Category category = Category.builder()
                .id(UUID.randomUUID())
                .name(req.name())
                .slug(req.slug())
                .parent(parent)
                .build();
        return categoryMapper.toResponse(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(UUID id, CategoryRequest req) {
        Category category = loadOrThrow(id);

        // Slug đổi → check unique. Tránh hit constraint violation tới DB
        // (cho UX message rõ hơn).
        if (!category.getSlug().equals(req.slug()) && categoryRepository.existsBySlug(req.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Category slug already exists");
        }
        // Self-parent check — đơn giản; cycle deeper Day 6 nếu thật sự cần.
        if (req.parentId() != null && req.parentId().equals(id)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Category cannot be its own parent");
        }

        category.setName(req.name());
        category.setSlug(req.slug());
        category.setParent(req.parentId() == null ? null : loadOrThrow(req.parentId()));
        return categoryMapper.toResponse(category);
    }

    @Transactional
    public void delete(UUID id) {
        Category category = loadOrThrow(id);
        // FK ON DELETE RESTRICT sẽ chặn nếu có product/child category —
        // bắt lỗi ở DB layer (DataIntegrityViolationException) sẽ bị
        // GlobalExceptionHandler catch fallback 500. Day 4-5 sẽ thêm
        // explicit check nếu cần message rõ hơn.
        categoryRepository.delete(category);
    }

    private Category loadOrThrow(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Category not found: " + id));
    }
}
