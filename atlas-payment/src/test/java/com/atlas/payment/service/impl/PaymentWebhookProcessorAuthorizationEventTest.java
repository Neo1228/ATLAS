package com.atlas.payment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 Sandbox가 보낸 {@code PAYMENT.AUTHORIZATION.VOIDED} 본문(EXP-094, ID 치환)을 처리해도 결제 시도·복구·환불
 * 상태는 바뀌지 않고 수신 기록만 남는다. ADR-0030: webhook만으로 상태를 바꾸거나 돈을 움직이지 않는다.
 * 누군가 이 이벤트로 시도를 VOIDED로 바꾸는 처리를 더하면(순서·중복이 보장되지 않는다) 이 테스트가 실패한다.
 */
class PaymentWebhookProcessorAuthorizationEventTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final PaymentWebhookEventRepository webhookEvents = mock(PaymentWebhookEventRepository.class);
    private final PaymentRecoveryRepository recovery = mock(PaymentRecoveryRepository.class);

    @Test
    void recordedAuthorizationVoidedEventIsOnlyRecorded() {
        JsonNode event = recorded("webhookAuthorizationVoided").get("body");
        JsonNode resource = event.get("resource");
        when(webhookEvents.insertReceived(any(), any(), any(), any())).thenReturn(true);

        PaymentWebhookResponse result = payments().processVerifiedPayPalWebhook(new PaymentWebhookEventResponse(
                "WH-recorded-1", event.get("event_type").asString(), resource.get("id").asString(),
                resource.get("status").asString(),
                resource.get("supplementary_data").get("related_ids").get("order_id").asString(),
                null, null, null));

        assertThat(event.get("event_type").asString()).isEqualTo("PAYMENT.AUTHORIZATION.VOIDED");
        assertThat(result.accepted()).isTrue();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.captureConfirmed()).isFalse();
        assertThat(result.refundOperationId()).isNull();
        verify(webhookEvents).insertReceived(any(), any(), any(), any());
        verify(webhookEvents).markProcessed(any(), any());
        verifyNoInteractions(attempts, recovery, refunds);
    }

    private PaymentServiceImpl payments() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return PaymentServiceFactory.create(attempts, refunds, webhookEvents, recovery, transactionManager,
                mock(PaymentProvider.class), mock(FxConversionService.class), "https://example.test");
    }

    private static JsonNode recorded(String name) {
        try (InputStream input = PaymentWebhookProcessorAuthorizationEventTest.class
                .getResourceAsStream("/paypal-sandbox/responses.json")) {
            return JSON.readTree(input).get(name);
        } catch (IOException exception) {
            throw new IllegalStateException("recorded PayPal Sandbox responses are missing", exception);
        }
    }
}
