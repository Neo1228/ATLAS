package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AtlasApiExceptionHandlerTest {

    private final AtlasApiExceptionHandler handler = new AtlasApiExceptionHandler();

    @Test
    void unexpectedErrorsUseAStableProblemCodeWithoutExposingExceptionDetails() {
        var response = handler.handleUnexpected(new IllegalStateException("database password leaked"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail())
                .isEqualTo("Unexpected server error")
                .doesNotContain("database password leaked");
    }
}
