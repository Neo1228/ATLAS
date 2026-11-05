package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.UpdateSkuPriceHttpRequest;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.service.ProductService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private final ProductService products;

    @PostMapping("/sellers/{sellerId}/products")
    @ResponseStatus(HttpStatus.CREATED)
    ProductResponse createProduct(@PathVariable UUID sellerId, @Valid @RequestBody CreateProductRequest request) {
        return products.createProduct(sellerId, request);
    }

    @GetMapping("/products")
    ProductListingPageResponse listProducts(@RequestParam(required = false) String query,
            @RequestParam(required = false) UUID sellerId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return page(sellerId, query, limit, cursor);
    }

    @GetMapping("/sellers/{sellerId}/products")
    ProductListingPageResponse listSellerProducts(@PathVariable UUID sellerId,
            @RequestParam(required = false) String query, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return page(sellerId, query, limit, cursor);
    }

    private ProductListingPageResponse page(UUID sellerId, String query, Integer requestedLimit, String cursor) {
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new CatalogException(CatalogException.Code.INVALID_REQUEST, "Product page limit is out of range");
        }
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        UUID cursorSeller = null;
        UUID cursorProduct = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = decoded.split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                cursorSeller = UUID.fromString(parts[0]);
                cursorProduct = UUID.fromString(parts[1]);
                if (sellerId != null && !sellerId.equals(cursorSeller)) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException exception) {
                throw new CatalogException(CatalogException.Code.INVALID_REQUEST, "Invalid product cursor");
            }
        }
        List<ProductListingResponse> fetched = products.listProductsPage(sellerId, keyword, cursorSeller,
                cursorProduct, limit);
        boolean hasMore = fetched.size() > limit;
        List<ProductListingResponse> items = fetched.subList(0, Math.min(limit, fetched.size()));
        String nextCursor = null;
        if (hasMore) {
            ProductListingResponse last = items.get(items.size() - 1);
            String value = last.product().sellerId() + ":" + last.product().productId();
            nextCursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
        return new ProductListingPageResponse(items, nextCursor);
    }

    @GetMapping("/products/{productId}")
    ProductListingResponse findProduct(@PathVariable UUID productId) { return products.findProduct(productId); }

    @PostMapping("/sellers/{sellerId}/products/{productId}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    SellerSkuResponse registerSku(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @Valid @RequestBody RegisterSellerSkuRequest request) {
        return products.registerSku(sellerId, productId, request);
    }

    @PutMapping("/sellers/{sellerId}/products/{productId}/skus/{skuId}/price")
    SellerSkuResponse updateSkuPrice(@PathVariable UUID sellerId, @PathVariable UUID productId,
            @PathVariable String skuId, @Valid @RequestBody UpdateSkuPriceHttpRequest request) {
        return products.updateSkuPrice(sellerId, productId, skuId, request.priceWon());
    }
}
