package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.exception.AdmissionException;
import com.atlas.order.exception.OrderException;

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
}
