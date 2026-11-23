package com.atlas.payment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import com.atlas.payment.service.FxConversionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PaymentCaptureCancellationFenceTest {
    @Test
    void cancellationFenceStopsAnotherServiceInstanceFromReservingCapture() {
        UUID checkoutId = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt(null, checkoutId, UUID.randomUUID(),
                PaymentState.AUTHORIZED, PaymentPhase.CAPTURE, true);
        PaymentAttemptRepository repository = mock(PaymentAttemptRepository.class);
        when(repository.findWithLockByCheckoutId(checkoutId)).thenReturn(Optional.of(attempt));
        when(repository.findByCheckoutId(checkoutId)).thenReturn(Optional.of(attempt));
        when(repository.findWithLockByAttemptId(attempt.getAttemptId())).thenReturn(Optional.of(attempt));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(inv -> new SimpleTransactionStatus());
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentServiceImpl canceller = service(repository, transactions, provider);
        PaymentServiceImpl reconciler = service(repository, transactions, provider);

        canceller.prohibitNewCheckoutCapture(checkoutId);
        assertThat(reconciler.reconcileCheckout(checkoutId, "reconcile").state()).isEqualTo(PaymentState.AUTHORIZED);
        verify(provider, never()).perform(any());
    }

    @Test
    void reservedOrUnknownCaptureStillReconcilesAfterFence() {
        for (PaymentState state : new PaymentState[]{PaymentState.CAPTURE_PENDING, PaymentState.UNKNOWN}) {
            UUID checkoutId = UUID.randomUUID();
            PaymentAttempt attempt = new PaymentAttempt(null, checkoutId, UUID.randomUUID(),
                    state, PaymentPhase.CAPTURE, true);
            attempt.setTotalWon(1_000);
            PaymentAttemptRepository repository = mock(PaymentAttemptRepository.class);
            when(repository.findWithLockByCheckoutId(checkoutId)).thenReturn(Optional.of(attempt));
            when(repository.findByCheckoutId(checkoutId)).thenReturn(Optional.of(attempt));
            when(repository.findWithLockByAttemptId(attempt.getAttemptId())).thenReturn(Optional.of(attempt));
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any(TransactionDefinition.class)))
                    .thenAnswer(inv -> new SimpleTransactionStatus());
            PaymentProvider provider = mock(PaymentProvider.class);
            when(provider.providerCurrency()).thenReturn("KRW");
            when(provider.perform(any())).thenReturn(new PaymentProviderResponse(
                    PaymentProviderResponse.Outcome.CAPTURED, null, null, "captured", null));
            PaymentServiceImpl service = service(repository, transactions, provider);

            service.prohibitNewCheckoutCapture(checkoutId);
            assertThat(service.reconcileCheckoutForCancellation(checkoutId, "cancel").state())
                    .isEqualTo(PaymentState.CAPTURED);
            verify(provider).perform(any());
        }
    }

    // 저장소를 공유하는 단위 테스트이므로 실제 DB 잠금 경합이나 롤백은 검증하지 않는다.
    private static PaymentServiceImpl service(PaymentAttemptRepository repository,
            PlatformTransactionManager transactions, PaymentProvider provider) {
        return new PaymentServiceImpl(repository, mock(PaymentRefundRepository.class),
                mock(PaymentWebhookEventRepository.class), mock(PaymentRecoveryRepository.class),
                transactions, provider, mock(FxConversionService.class), "https://example.test");
    }
}
