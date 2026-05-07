package com.ecom.product.mapper;

import com.ecom.product.domain.Category;
import com.ecom.product.web.dto.CategoryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    @Mapping(target = "parentId", source = "parent.id")
    CategoryResponse toResponse(Category category);
}
