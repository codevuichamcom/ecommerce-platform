package com.ecom.product.web.dto;

import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String slug,
        UUID parentId
) {}
