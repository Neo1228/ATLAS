package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.exception.AdmissionException;
import com.atlas.cart.exception.CartException;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.order.exception.OrderException;
import com.atlas.settlement.exception.SettlementException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedErrorsUseAStableProblemCodeWithoutExposingExceptionDetails() {
        var response = handler.handleUnexpected(new IllegalStateException("database password leaked"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail())
                .isEqualTo("Unexpected server error")
                .doesNotContain("database password leaked");
    }

    @Test
    void moduleExceptionCodesMapToStableClientStatuses() {
        var invalid = handler.moduleException(new AdmissionException(
                AdmissionException.Code.INVALID_REQUEST, "invalid ticket request"));
        assertThat(invalid.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(invalid.getBody().getProperties()).containsEntry("code", "INVALID_REQUEST");

        var missing = handler.moduleException(new OrderException(OrderException.Code.NOT_FOUND, "missing order"));
        assertThat(missing.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(missing.getBody().getProperties()).containsEntry("code", "ORDER_NOT_FOUND");
    }

    @Test
    void catalogCartAndInventoryErrorsUseStableProblemCodes() {
        var missingCart = handler.moduleException(new CartException(CartException.Code.NOT_FOUND, "missing cart"));
        assertThat(missingCart.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(missingCart.getBody().getProperties()).containsEntry("code", "CART_NOT_FOUND");

        var cartConflict = handler.moduleException(
                new CartException(CartException.Code.CONFLICT, "cart merge confirmation required"));
        assertThat(cartConflict.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());

        assertThat(cartConflict.getBody().getProperties()).containsEntry("code", "CONFLICT");
        var missingCatalogItem = handler.moduleException(
                new CatalogException(CatalogException.Code.NOT_FOUND, "missing product"));
        assertThat(missingCatalogItem.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(missingCatalogItem.getBody().getProperties()).containsEntry("code", "CATALOG_RESOURCE_NOT_FOUND");

        var insufficientStock = handler.moduleException(
                new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK, "insufficient stock"));
        assertThat(insufficientStock.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(insufficientStock.getBody().getProperties()).containsEntry("code", "INSUFFICIENT_STOCK");
    }
    @Test
    void unavailableProductMapsToConflictWithStableCode() {
        var response = handler.moduleException(new CatalogException(
                CatalogException.Code.PRODUCT_NOT_ORDERABLE, "Product is unavailable"));
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getBody().getProperties()).containsEntry("code", "PRODUCT_NOT_ORDERABLE");
    }


    @Test
    void settlementInvalidRequestMapsToBadRequest() {
        var response = handler.moduleException(new SettlementException(
                SettlementException.Code.INVALID_REQUEST, "Invalid statement request"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().getProperties()).containsEntry("code", "INVALID_REQUEST");
    }
}
