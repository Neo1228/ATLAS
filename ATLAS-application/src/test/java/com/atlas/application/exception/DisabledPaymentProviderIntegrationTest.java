package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class DisabledPaymentProviderIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas");

    @DynamicPropertySource
    static void disabledProviderProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.payment.provider", () -> "disabled");
        properties.add("atlas.payment.paypal.client-id", () -> "");
        properties.add("atlas.payment.paypal.client-secret", () -> "");
        properties.add("atlas.payment.paypal.webhook-id", () -> "");
    }

    @Autowired
    private PaymentService payments;

    @Test
    void paymentRequestFailsClosedAndMapsDisabledProviderToServiceUnavailable() {
        PaymentException failure = assertThrows(PaymentException.class,
                () -> payments.submitInitial(UUID.randomUUID(), BigDecimal.valueOf(1200)));
        assertThat(failure.code()).isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_DISABLED);

        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler().moduleException(failure);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "PAYMENT_PROVIDER_DISABLED");
    }
}
