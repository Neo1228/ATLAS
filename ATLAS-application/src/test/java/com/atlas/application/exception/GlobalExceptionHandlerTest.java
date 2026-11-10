package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.exception.AdmissionException;
import com.atlas.cart.exception.CartException;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.order.exception.OrderException;
import com.atlas.payment.exception.PaymentException;
import com.atlas.settlement.exception.SettlementException;
import org.slf4j.MDC;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void errorResponseIncludesRequestIdFromCurrentRequestContext() {
        MDC.put("requestId", "request-123");
        try {
            var response = handler.moduleException(new OrderException(
                    OrderException.Code.CONFLICT, "Conflict"));
            assertThat(response.getBody().getProperties()).containsEntry("requestId", "request-123");
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void unexpectedErrorsUseAStableProblemCodeWithoutExposingExceptionDetails() {
        var response = handler.handleUnexpected(new IllegalStateException("database password leaked"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail())
                .isEqualTo("서버에서 요청을 처리하지 못했습니다. 요청 ID를 고객 지원에 전달해 주세요.")
                .doesNotContain("database password leaked");
    }
    @Test
    void serverErrorResponseIsSanitizedAndMappedToInternalError() {
        var response = handler.handleUnexpected(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "provider detail should stay server-side"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail())
                .isEqualTo("서버에서 요청을 처리하지 못했습니다. 요청 ID를 고객 지원에 전달해 주세요.")
                .doesNotContain("provider detail");
    }


    @Test
    void moduleExceptionCodesMapToStableClientStatuses() {
        var invalid = handler.moduleException(new AdmissionException(
                AdmissionException.Code.INVALID_REQUEST, "invalid ticket request"));
        assertThat(invalid.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(invalid.getBody().getProperties()).containsEntry("code", "INVALID_REQUEST");

        assertThat(invalid.getBody().getDetail()).isEqualTo("요청 정보를 확인해 주세요.");
        var missing = handler.moduleException(new OrderException(OrderException.Code.NOT_FOUND, "missing order"));
        assertThat(missing.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(missing.getBody().getProperties()).containsEntry("code", "ORDER_NOT_FOUND");
        assertThat(missing.getBody().getDetail()).isEqualTo("요청한 정보를 찾을 수 없습니다.");
    }

    @Test
    void providerUnavailableUsesKoreanRetryGuidanceAndStableCode() {
        var response = handler.moduleException(new PaymentException(
                PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE, "PayPal API request failed"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getBody().getProperties()).containsEntry("code", "PAYMENT_PROVIDER_UNAVAILABLE");
        assertThat(response.getBody().getDetail())
                .isEqualTo("결제 서비스에 일시적으로 연결할 수 없습니다. 결제 상태를 확인한 뒤 같은 요청으로 다시 시도해 주세요.")
                .doesNotContain("PayPal API request failed");
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
    void settlementErrorsMapToStableClientStatuses() {
        var invalid = handler.moduleException(new SettlementException(
                SettlementException.Code.INVALID_REQUEST, "Invalid statement request"));
        assertThat(invalid.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(invalid.getBody().getProperties()).containsEntry("code", "INVALID_REQUEST");

        var conflict = handler.moduleException(new SettlementException(
                SettlementException.Code.CONFLICT, "Capture facts conflict"));
        assertThat(conflict.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(conflict.getBody().getProperties()).containsEntry("code", "CONFLICT");
    }
}
