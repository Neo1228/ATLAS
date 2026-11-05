package com.atlas.application.controller.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.ProductService.ProductListingPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProductControllerPaginationTest {
    @Test
    void mapsHttpCursorAndSerializesNextCursorWhileDelegatingRawRequest() {
        ProductService service = mock(ProductService.class);
        ProductController controller = new ProductController(service);
        UUID sellerA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sellerB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID productA = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID productB = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(service.listProductsPage(null, "  bLuE ", 1, null, null))
                .thenReturn(new ProductListingPage(List.of(listing(sellerA, productA, "Blue bottle")), true));
        when(service.listProductsPage(null, "bLuE", 1, sellerA, productA))
                .thenReturn(new ProductListingPage(List.of(listing(sellerB, productB, "BLUE mug")), false));

        var first = controller.listProducts("  bLuE ", null, 1, null);
        assertThat(first.items()).extracting(item -> item.product().name()).containsExactly("Blue bottle");
        assertThat(decode(first.nextCursor())).isEqualTo(sellerA + ":" + productA);

        var second = controller.listProducts("bLuE", null, 1, first.nextCursor());
        assertThat(second.items()).extracting(item -> item.product().name()).containsExactly("BLUE mug");
        assertThat(second.nextCursor()).isNull();
        verify(service).listProductsPage(null, "  bLuE ", 1, null, null);
        verify(service).listProductsPage(null, "bLuE", 1, sellerA, productA);
    }

    @Test
    void malformedCursorUsesHttpBadRequestWithoutCallingCatalogService() {
        ProductService service = mock(ProductService.class);
        ProductController controller = new ProductController(service);

        assertThatThrownBy(() -> controller.listProducts(null, null, null, "not-a-cursor"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void blankCursorIsAbsentAndEmptyServicePageIsReturnedAsIs() {
        ProductService service = mock(ProductService.class);
        ProductController controller = new ProductController(service);
        when(service.listProductsPage(null, null, null, null, null))
                .thenReturn(new ProductListingPage(List.of(), false));

        var empty = controller.listProducts(null, null, null, " ");

        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        verify(service).listProductsPage(null, null, null, null, null);
    }

    private static String decode(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    private static ProductListingResponse listing(UUID sellerId, UUID productId, String name) {
        return new ProductListingResponse(new ProductResponse(productId, sellerId, name, "description"), List.of());
    }
}
