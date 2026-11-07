package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.UpdateProductChannelAvailabilityHttpRequest;
import com.atlas.application.controller.dto.UpdateProductStatusHttpRequest;
import com.atlas.application.controller.dto.UpdateSkuPriceHttpRequest;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.request.UpdateProductCategoryAssignmentRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.ProductService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService products;

    @PostMapping("/sellers/{sellerId}/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@PathVariable UUID sellerId, @Valid @RequestBody CreateProductRequest request) {
        return products.createProduct(sellerId, request);
    }

    @GetMapping("/products")
    public ProductListingPageResponse listProducts(@RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) String query, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return products.listProductsPage(sellerId, categoryId, query, limit, cursor);
    }

    @GetMapping("/sellers/{sellerId}/products")
    public ProductListingPageResponse listSellerProducts(@PathVariable UUID sellerId,
            @RequestParam(required = false) String query, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
 return products.listSellerProductsPage(sellerId, categoryId, query, limit, cursor);
    }

    @GetMapping("/products/{productId}")
    public ProductListingResponse findProduct(@PathVariable UUID productId) {
        return products.findProduct(productId);
    }

 @PutMapping("/sellers/{sellerId}/products/{productId}/status")
 public ProductResponse updateProductStatus(@PathVariable UUID sellerId, @PathVariable UUID productId,
 @Valid @RequestBody UpdateProductStatusHttpRequest request) {
 return products.updateStatus(sellerId, productId, request.status());
 }

 @PutMapping("/sellers/{sellerId}/products/{productId}/channel-availability")
 public ProductResponse updateProductChannelAvailability(@PathVariable UUID sellerId, @PathVariable UUID productId,
 @Valid @RequestBody UpdateProductChannelAvailabilityHttpRequest request) {
 return products.updateChannelAvailability(sellerId, productId, request.channel(), request.available());
 }

    @PutMapping("/sellers/{sellerId}/products/{productId}/category")
    public ProductResponse updateProductCategory(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductCategoryAssignmentRequest request) {
        return products.updateProductCategory(sellerId, productId, request.categoryId());
    }

    @PostMapping("/sellers/{sellerId}/products/{productId}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerSkuResponse registerSku(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @Valid @RequestBody RegisterSellerSkuRequest request) {
        return products.registerSku(sellerId, productId, request);
    }

    @PutMapping("/sellers/{sellerId}/products/{productId}/skus/{skuId}/price")
    public SellerSkuResponse updateSkuPrice(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @PathVariable String skuId, @Valid @RequestBody UpdateSkuPriceHttpRequest request) {
        return products.updateSkuPrice(sellerId, productId, skuId, request.priceWon());
    }
}
