package com.atlas.application.controller.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.service.ProductService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductControllerPaginationTest {
    @Test
    void trimsCaseInsensitiveSubstringAndPaginatesInSellerProductOrder() {
        ProductService service = mock(ProductService.class);
        ProductController controller = new ProductController(service);
        UUID sellerA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sellerB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID productA = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID productB = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(service.listProductsPage(null, "blue", null, null, 1)).thenReturn(List.of(
                listing(sellerA, productA, "Blue bottle"), listing(sellerB, productB, "BLUE mug")));
        when(service.listProductsPage(null, "blue", sellerA, productA, 1))
                .thenReturn(List.of(listing(sellerB, productB, "BLUE mug")));

        var first = controller.listProducts("  bLuE ", null, 1, null);
        assertThat(first.items()).extracting(item -> item.product().name()).containsExactly("Blue bottle");
        assertThat(first.nextCursor()).isNotBlank();

        var second = controller.listProducts("bLuE", null, 1, first.nextCursor());
        assertThat(second.items()).extracting(item -> item.product().name()).containsExactly("BLUE mug");
        assertThat(second.nextCursor()).isNull();
        verify(service).listProductsPage(null, "blue", null, null, 1);
        verify(service).listProductsPage(null, "blue", sellerA, productA, 1);
    }

    @Test
    void returnsEmptyPageForNoMatchesAndRejectsInvalidLimitsAndScopedCursorMismatch() {
        ProductService service = mock(ProductService.class);
        ProductController controller = new ProductController(service);
        UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(service.listProductsPage(null, "absent", null, null, 10)).thenReturn(List.of());

        var empty = controller.listProducts(" absent ", null, null, null);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        verify(service).listProductsPage(null, "absent", null, null, 10);
        when(service.listProductsPage(null, "", null, null, 100)).thenReturn(List.of());
        assertThat(controller.listProducts(null, null, 100, null).items()).isEmpty();
        verify(service).listProductsPage(null, "", null, null, 100);

        for (int invalidLimit : List.of(-1, 0, 101)) {
            assertThatThrownBy(() -> controller.listProducts(null, null, invalidLimit, null))
                    .isInstanceOf(CatalogException.class);
        }
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (sellerId + ":" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.listSellerProducts(UUID.randomUUID(), null, 10, cursor))
                .isInstanceOf(CatalogException.class);
    }

    private static ProductListingResponse listing(UUID sellerId, UUID productId, String name) {
        return new ProductListingResponse(new ProductResponse(productId, sellerId, name, "description"), List.of());
    }
}
