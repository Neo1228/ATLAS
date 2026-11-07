package com.atlas.application.controller.seller;

import com.atlas.catalog.dto.request.CreateProductCategoryRequest;
import com.atlas.catalog.dto.request.UpdateProductCategoryRequest;
import com.atlas.catalog.dto.response.ProductCategoryResponse;
import com.atlas.catalog.service.ProductCategoryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}/product-categories")
public class ProductCategoryController {

    private final ProductCategoryService categories;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductCategoryResponse create(@PathVariable UUID sellerId,
            @Valid @RequestBody CreateProductCategoryRequest request) {
        return categories.createCategory(sellerId, request);
    }

    @GetMapping
    public List<ProductCategoryResponse> list(@PathVariable UUID sellerId) {
        return categories.listCategories(sellerId);
    }

    @PutMapping("/{categoryId}")
    public ProductCategoryResponse updateName(@PathVariable UUID sellerId, @PathVariable UUID categoryId,
            @Valid @RequestBody UpdateProductCategoryRequest request) {
        return categories.updateCategoryName(sellerId, categoryId, request);
    }
}
