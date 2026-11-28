package com.atlas.application.controller.catalog;

import com.atlas.application.dto.request.CatalogHttpRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductCategoryRequest;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public CatalogResponse.Product createProduct(@PathVariable UUID sellerId, @Valid @RequestBody ProductRequest.CreateProduct request) {
        return products.createProduct(sellerId, request);
    }

    @GetMapping("/products")
    public CatalogResponse.ProductListingPage listProducts(@RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) String query, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Long minPriceWon, @RequestParam(required = false) Long maxPriceWon,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return products.listProductsPage(sellerId, categoryId, query, minPriceWon, maxPriceWon, limit, cursor);
    }

    /** 관련도 순 공개 검색(ADR-0109). LLM 없이도 쓰는 API이고, 쇼핑 비서의 search_products 도구도 같은 서비스를 부른다. */
    @GetMapping("/products/search")
    public CatalogResponse.ProductListingPage searchProducts(@RequestParam("q") String query,
            @RequestParam(required = false) Long minPriceWon, @RequestParam(required = false) Long maxPriceWon,
            @RequestParam(required = false) Integer limit) {
        return products.searchProducts(query, minPriceWon, maxPriceWon, limit);
    }

    @GetMapping("/sellers/{sellerId}/products")
    public CatalogResponse.ProductListingPage listSellerProducts(@PathVariable UUID sellerId,
            @RequestParam(required = false) String query, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
 return products.listSellerProductsPage(sellerId, categoryId, query, limit, cursor);
    }

    @GetMapping("/products/{productId}")
    public CatalogResponse.ProductListing findProduct(@PathVariable UUID productId) {
        return products.findProduct(productId);
    }

 @PutMapping("/sellers/{sellerId}/products/{productId}/status")
 public CatalogResponse.Product updateProductStatus(@PathVariable UUID sellerId, @PathVariable UUID productId,
 @Valid @RequestBody CatalogHttpRequest.UpdateProductStatus request) {
 return products.updateStatus(sellerId, productId, request.status());
 }

 @PutMapping("/sellers/{sellerId}/products/{productId}/channel-availability")
 public CatalogResponse.Product updateProductChannelAvailability(@PathVariable UUID sellerId, @PathVariable UUID productId,
 @Valid @RequestBody CatalogHttpRequest.UpdateProductChannelAvailability request) {
 return products.updateChannelAvailability(sellerId, productId, request.channel(), request.available());
 }

    @PutMapping("/sellers/{sellerId}/products/{productId}/categories")
    public CatalogResponse.Product updateProductCategories(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @Valid @RequestBody ProductCategoryRequest.UpdateAssignment request) {
        return products.updateProductCategories(sellerId, productId, request.categoryIds());
    }

    @PostMapping("/sellers/{sellerId}/products/{productId}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogResponse.SellerSku registerSku(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @Valid @RequestBody ProductRequest.RegisterSellerSku request) {
        return products.registerSku(sellerId, productId, request);
    }

    @PutMapping("/sellers/{sellerId}/products/{productId}/skus/{skuId}/price")
    public CatalogResponse.SellerSku updateSkuPrice(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @PathVariable String skuId, @Valid @RequestBody CatalogHttpRequest.UpdateSkuPrice request) {
        return products.updateSkuPrice(sellerId, productId, skuId, request.priceWon());
    }
    @PutMapping("/sellers/{sellerId}/products/{productId}/skus/{skuId}/sale-price")
    public CatalogResponse.SellerSku setSkuSalePrice(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @PathVariable String skuId, @Valid @RequestBody CatalogHttpRequest.SetSkuSalePrice request) {
        return products.setSkuSalePrice(sellerId, productId, skuId, request.salePriceWon(),
                request.startsAt(), request.endsAt());
    }

    @DeleteMapping("/sellers/{sellerId}/products/{productId}/skus/{skuId}/sale-price")
    public CatalogResponse.SellerSku clearSkuSalePrice(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @PathVariable String skuId) {
        return products.clearSkuSalePrice(sellerId, productId, skuId);
    }
}