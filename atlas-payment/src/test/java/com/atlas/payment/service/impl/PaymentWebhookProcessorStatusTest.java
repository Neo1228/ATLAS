package com.atlas.payment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.model.PaymentRefundState;
import com.atlas.payment.persistence.entity.PaymentRefundOperation;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 환불 웹훅의 resource status는 대소문자와 무관하게 기대 상태와 비교하고, 그 밖의 값은 거부한다.
 * 저장하는 환불 상태 증거는 PayPal이 보낸 원문 그대로다.
 */
class PaymentWebhookProcessorStatusTest {
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final PaymentWebhookEventRepository webhookEvents = mock(PaymentWebhookEventRepository.class);

    @ParameterizedTest
    @CsvSource({"COMPLETED, true", "completed, true", "PENDING, false", "NEW_REFUND_STATUS, false"})
    void refundCompletedWebhookAcceptsOnlyACompletedResourceStatus(String resourceStatus, boolean accepted) {
        UUID refundOperationId = UUID.randomUUID();
        when(refunds.findByProviderRefundIdForUpdate("refund-1")).thenReturn(Optional.of(new PaymentRefundOperation(
                refundOperationId, UUID.randomUUID(), "capture-1", "refund-key", 13_000L, "KRW", 1000L, "USD",
                UUID.randomUUID(), "refund-1", "PENDING", PaymentRefundState.PENDING, Instant.EPOCH, Instant.EPOCH)));
        when(webhookEvents.insertReceived(any(), any(), any(), any())).thenReturn(true);

        PaymentWebhookResponse result = payments().processVerifiedPayPalWebhook(new PaymentWebhookEventResponse(
                "event-1", "PAYMENT.REFUND.COMPLETED", "refund-1", resourceStatus, null, "capture-1",
                new BigDecimal("10.00"), "USD"));

        assertThat(result.accepted()).isEqualTo(accepted);
        if (accepted) {
            verify(refunds).updateResult(eq(refundOperationId), eq(PaymentRefundState.COMPLETED), eq("refund-1"),
                    eq(resourceStatus), any());
        } else {
            verify(refunds, never()).updateResult(any(), any(), any(), any(), any());
        }
    }

    private PaymentServiceImpl payments() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return PaymentServiceFactory.create(mock(PaymentAttemptRepository.class), refunds, webhookEvents,
                mock(PaymentRecoveryRepository.class), transactionManager, mock(PaymentProvider.class),
                mock(FxConversionService.class), "https://example.test");
    }
}
