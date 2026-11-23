package com.atlas.payment.service.impl;

import com.atlas.payment.service.PayPalWebhookOperatorService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.payment.exception.PaymentException.Code;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.repository.PayPalWebhookOperatorRepository;
import com.atlas.payment.persistence.projection.RequeueRecord;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PayPalWebhookOperatorServiceImplTest {
    private static final String EVENT = "event-1";
    private static final String KEY = "key-1";
    private static final String ACTOR = "webhook-operator";
    private static final String REASON = "manual review";

    private final PayPalWebhookOperatorRepository events = mock(PayPalWebhookOperatorRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private PayPalWebhookOperatorServiceImpl service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        service = new PayPalWebhookOperatorServiceImpl(events, transactions);
    }

    @Test
    void sameKeyReplaySurvivesQueueRowDeletionAndDoesNotTouchQueueOrProvider() {
        when(events.findRequeueRecord(KEY)).thenReturn(Optional.of(new RequeueRecord(EVENT, ACTOR, REASON, "REQUEUED")));

        assertThat(service.requeue(EVENT, KEY, ACTOR, REASON).outcome()).isEqualTo("REQUEUED");
        verify(events, never()).lockExhausted(anyString());
        verify(events, never()).requeueExhausted(anyString(), anyInt(), anyInt());
        verify(events, never()).recordRequeue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentActorEventOrReason() {
        when(events.findRequeueRecord(KEY)).thenReturn(Optional.of(new RequeueRecord(EVENT, ACTOR, REASON, "REQUEUED")));

        assertConflict(() -> service.requeue(EVENT, KEY, "other-operator", REASON));
        assertConflict(() -> service.requeue("event-2", KEY, ACTOR, REASON));
        assertConflict(() -> service.requeue(EVENT, KEY, ACTOR, "other reason"));
        verify(events, never()).requeueExhausted(anyString(), anyInt(), anyInt());
    }

    @Test
    void durableAuditHistoryStopsFourthRequeue() {
        when(events.findRequeueRecord(KEY)).thenReturn(Optional.empty());
        when(events.lockExhausted(EVENT)).thenReturn(true);
        when(events.countRequeues(EVENT)).thenReturn(3);

        assertConflict(() -> service.requeue(EVENT, KEY, ACTOR, REASON));
        verify(events, never()).requeueExhausted(anyString(), anyInt(), anyInt());
        verify(events, never()).recordRequeue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aNewRequeueOnlyUpdatesQueueScheduleAndWritesAudit() {
        when(events.findRequeueRecord(KEY)).thenReturn(Optional.empty());
        when(events.lockExhausted(EVENT)).thenReturn(true);
        when(events.countRequeues(EVENT)).thenReturn(0);
        when(events.requeueExhausted(EVENT, 1, PayPalWebhookOperatorService.MAX_MANUAL_REQUEUES)).thenReturn(true);

        assertThat(service.requeue(EVENT, KEY, ACTOR, REASON).outcome()).isEqualTo("REQUEUED");
        verify(events).requeueExhausted(EVENT, 1, PayPalWebhookOperatorService.MAX_MANUAL_REQUEUES);
        verify(events).recordRequeue(KEY, EVENT, ACTOR, REASON);
    }

    @Test
    void unknownEventIsNotFoundAndNonExhaustedEventIsConflict() {
        when(events.findRequeueRecord(KEY)).thenReturn(Optional.empty());
        when(events.lockExhausted(EVENT)).thenReturn(false);

        when(events.exists(EVENT)).thenReturn(false);
        assertThatThrownBy(() -> service.requeue(EVENT, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(PaymentException.class,
                        error -> assertThat(error.code()).isEqualTo(Code.NOT_FOUND));

        when(events.exists(EVENT)).thenReturn(true);
        assertConflict(() -> service.requeue(EVENT, KEY, ACTOR, REASON));
    }

    @Test
    void lostUpdateRaceAfterTheLockIsReportedAsConflictWithoutAnAuditRow() {
        when(events.findRequeueRecord(KEY)).thenReturn(Optional.empty());
        when(events.lockExhausted(EVENT)).thenReturn(true);
        when(events.countRequeues(EVENT)).thenReturn(0);
        when(events.requeueExhausted(EVENT, 1, PayPalWebhookOperatorService.MAX_MANUAL_REQUEUES)).thenReturn(false);

        assertConflict(() -> service.requeue(EVENT, KEY, ACTOR, REASON));
        verify(events, never()).recordRequeue(anyString(), anyString(), anyString(), anyString());
    }

    private static void assertConflict(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(PaymentException.class,
                        error -> assertThat(error.code()).isEqualTo(Code.CONFLICT));
    }
}
