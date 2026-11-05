package com.atlas.application.controller.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.application.exception.GlobalExceptionHandler;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.ProductService.ProductListingPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
    void malformedCursorsReturnTypedProblemDetailWithoutCallingCatalogService() throws Exception {
        ProductService service = mock(ProductService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<String> malformedCursors = List.of(
                "!not-base64!",
                encode("invalid:cursor:parts"),
                encode("not-a-uuid:00000000-0000-0000-0000-000000000001"));

        for (String cursor : malformedCursors) {
            mockMvc.perform(get("/api/products").param("cursor", cursor))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("Invalid product cursor"));
        }
        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("cursor", malformedCursors.get(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Invalid product cursor"));
        verifyNoInteractions(service);
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

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ProductListingResponse listing(UUID sellerId, UUID productId, String name) {
        return new ProductListingResponse(new ProductResponse(productId, sellerId, name, "description"), List.of());
    }
}
