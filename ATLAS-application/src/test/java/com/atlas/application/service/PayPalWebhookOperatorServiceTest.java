package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class PayPalWebhookOperatorServiceTest {
    private static final String EVENT = "event-1";
    private static final String KEY = "key-1";
    private static final String ACTOR = "webhook-operator";
    private static final String REASON = "manual review";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private PayPalWebhookOperatorService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        service = new PayPalWebhookOperatorService(jdbc, transactions);
    }

    @Test
    void sameKeyReplaySurvivesQueueRowDeletionAndDoesNotTouchQueueOrProvider() {
        doAnswer(invocation -> List.<String[]>of(new String[] {EVENT, ACTOR, REASON, "REQUEUED"}))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThat(service.requeue(EVENT, KEY, ACTOR, REASON).outcome()).isEqualTo("REQUEUED");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentActorEventOrReason() {
        doAnswer(invocation -> List.<String[]>of(new String[] {EVENT, ACTOR, REASON, "REQUEUED"}))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(() -> service.requeue(EVENT, KEY, "other-operator", REASON))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void durableAuditHistoryStopsFourthRequeue() {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("idempotency_key")) return List.of();
            if (sql.contains("FOR UPDATE")) return List.of(EVENT);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(3);

        assertThatThrownBy(() -> service.requeue(EVENT, KEY, ACTOR, REASON))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aNewRequeueOnlyUpdatesQueueScheduleAndWritesAudit() {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("idempotency_key")) return List.of();
            if (sql.contains("FOR UPDATE")) return List.of(EVENT);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(service.requeue(EVENT, KEY, ACTOR, REASON).outcome()).isEqualTo("REQUEUED");
        verify(jdbc).update(contains("attempt_count = 0"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO paypal_webhook_retry_operator_audit"), any(Object[].class));
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }
}
