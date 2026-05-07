package com.ecom.product.mapper;

import com.ecom.product.domain.Product;
import com.ecom.product.web.dto.ProductResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct = compile-time mapping codegen (zero reflection runtime cost).
 * KHÔNG dùng ModelMapper — reflection nặng, mistype field không catch
 * compile time. KHÔNG hand-roll mapping — boilerplate dễ sai khi add field.
 */
@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "categoryId",   source = "category.id")
    @Mapping(target = "categorySlug", source = "category.slug")
    ProductResponse toResponse(Product product);
}
