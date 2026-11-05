package com.atlas.application.controller.seller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.application.exception.GlobalExceptionHandler;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.service.ProductService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductControllerPaginationTest {
    @Test
    void malformedCursorUsesTypedProblemDetailForBothListingEndpoints() throws Exception {
        ProductService service = mock(ProductService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String cursor = "invalid-cursor";
        CatalogException invalidCursor = new CatalogException(
                CatalogException.Code.INVALID_REQUEST, "Invalid product cursor");
        when(service.listProductsPage(null, null, null, cursor)).thenThrow(invalidCursor);
        when(service.listProductsPage(sellerId, null, null, cursor)).thenThrow(invalidCursor);

        mockMvc.perform(get("/api/products").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Invalid product cursor"));
        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId).param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Invalid product cursor"));
    }
}
