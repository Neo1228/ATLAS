package com.atlas.application.service;

import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.service.AccountNotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 알림 outbox(V9)의 인증 메일을 보낸다(ADR-0102, EXP-113). 전용 scheduler 스레드 1개에서 돌아 SMTP가 멈춰도
 * HTTP 요청 스레드와 다른 sweep을 붙잡지 않는다.
 *
 * <p>{@code atlas.account.notification.outbox.enabled}가 기본값 {@code true}일 때 등록된다. 같은 키가 요청 경로를 outbox
 * 기록으로 바꾸므로, 켜고 끄는 일은 이 키 하나로 한다. {@code false}는 되돌림 스위치다(커밋 뒤 요청 스레드에서 직접
 * 전송). 끄면 남은 대기 행은 그대로 있다가 다시 켤 때 보낸다.
 */
@Component
@ConditionalOnProperty(name = "atlas.account.notification.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class AccountNotificationWorker {
    private static final String OUTCOMES_METRIC = "atlas.account.notification.outcomes";
    private static final String DELAY_METRIC = "atlas.account.notification.delivery.delay";
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountNotificationWorker.class);
    private final AccountNotificationService notifications;
    private final MeterRegistry meters;
    private final int batchSize;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong lastFailureLogNanos = new AtomicLong();

    public AccountNotificationWorker(AccountNotificationService notifications, MeterRegistry meters,
            @Value("${atlas.account.notification.worker.batch-size:20}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("account notification batch size is invalid");
        }
        this.notifications = Objects.requireNonNull(notifications);
        this.meters = Objects.requireNonNull(meters);
        this.batchSize = batchSize;
        Gauge.builder("atlas.account.notification.pending", pending, AtomicLong::get).register(meters);
        Gauge.builder("atlas.account.notification.failed", failed, AtomicLong::get).register(meters);
        Gauge.builder("atlas.account.notification.oldest_pending_age", oldestPendingAgeSeconds, AtomicLong::get)
                .baseUnit("seconds").register(meters);
    }

    @Scheduled(initialDelayString = "${atlas.account.notification.worker.initial-delay:PT1S}",
            fixedDelayString = "${atlas.account.notification.worker.poll-interval:PT1S}",
            scheduler = "accountNotificationTaskScheduler")
    public void deliverDue() {
        try {
            List<AccountResponse.NotificationDelivery> deliveries = notifications.deliverDue(batchSize);
            for (AccountResponse.NotificationDelivery delivery : deliveries) {
                Counter.builder(OUTCOMES_METRIC).tag("outcome", delivery.outcome())
                        .register(meters).increment();
                if (delivery.sent()) {
                    Timer.builder(DELAY_METRIC).register(meters).record(delivery.sinceRequested());
                }
            }
            AccountResponse.NotificationBacklog backlog = notifications.backlog();
            pending.set(backlog.pending());
            failed.set(backlog.failed());
            oldestPendingAgeSeconds.set(backlog.oldestPendingAge().toSeconds());
        } catch (RuntimeException failure) {
            // DB 장애 등으로 한 주기가 실패해도 행은 남아 있으므로 다음 주기에 다시 시도한다.
            long now = System.nanoTime();
            long previous = lastFailureLogNanos.get();
            if ((previous == 0 || now - previous >= FAILURE_LOG_INTERVAL_NANOS)
                    && lastFailureLogNanos.compareAndSet(previous, now)) {
                LOGGER.warn("Account notification poll failed; failureType={}", failure.getClass().getSimpleName());
            }
            Counter.builder(OUTCOMES_METRIC).tag("outcome", "poll_failure").register(meters).increment();
        }
    }
}
