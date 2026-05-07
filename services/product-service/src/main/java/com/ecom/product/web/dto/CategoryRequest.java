package com.ecom.product.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Slug pattern: lowercase + dash, không có dấu Việt — caller chịu trách
 * nhiệm slugify trước. Backend chỉ validate format, không tự generate
 * (vì rule slug có thể đổi theo SEO requirement).
 */
public record CategoryRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 150) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "slug must be lowercase-dash format") String slug,
        UUID parentId
) {}
