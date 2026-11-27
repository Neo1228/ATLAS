package com.atlas.application.service;

import com.atlas.application.persistence.projection.LockWaitStats;
import com.atlas.application.persistence.projection.QueueTableStats;
import com.atlas.application.repository.DatabaseActivityRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EDA 전환 신호 중 DB 통계로만 볼 수 있는 것을 주기적으로 읽어 게이지로 낸다(기본 꺼짐, ADR-0103).
 *
 * <ul>
 *   <li>큐 표 부풀음(T1): 표마다 {@code atlas.db.queue_table.dead_tuples}, {@code live_tuples}, {@code total_bytes},
 *       {@code seconds_since_vacuum}. 큐 표는 청구·완료 때 행을 고쳐 써서 죽은 튜플이 쌓인다.</li>
 *   <li>락 대기(T6·T7): 락 종류마다 {@code atlas.db.lock_wait.sessions}, {@code atlas.db.lock_wait.oldest_seconds}.
 *       판매자 지급 락과 SKU 재고 락은 모두 advisory 락이라 이 게이지에서 용도를 가를 수 없다. 값이 임계를 넘으면
 *       운영 가이드의 {@code pg_blocking_pids} 질의로 붙잡은 세션을 찾는다.</li>
 * </ul>
 *
 * <p>스크레이프마다 DB를 읽지 않도록 표본을 주기로 갱신하고 게이지는 마지막 표본을 돌려준다. 표본에 없던 락 종류는
 * 0으로 되돌린다(대기가 끝났는데 마지막 값이 남지 않게). 표나 판매자 같은 고카디널리티 라벨은 붙이지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "atlas.observability.database-signals", name = "enabled", havingValue = "true")
public class DatabaseSignalMetrics {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSignalMetrics.class);

    private final DatabaseActivityRepository activity;
    private final MeterRegistry meters;
    private final List<String> queueTables;
    private final Map<String, AtomicLong[]> tableValues = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong[]> lockValues = new ConcurrentHashMap<>();

    public DatabaseSignalMetrics(DatabaseActivityRepository activity, MeterRegistry meters,
            @Value("${atlas.observability.database-signals.queue-tables:payment_recovery_job,paypal_webhook_retry,"
                    + "settlement_payout_dispatch_outbox,order_inventory_event}") List<String> queueTables) {
        this.activity = Objects.requireNonNull(activity);
        this.meters = Objects.requireNonNull(meters);
        this.queueTables = List.copyOf(queueTables);
    }

    @Scheduled(fixedDelayString = "${atlas.observability.database-signals.poll-interval:PT30S}",
            initialDelayString = "${atlas.observability.database-signals.initial-delay:PT30S}")
    public void refresh() {
        try {
            for (QueueTableStats stats : activity.queueTableStats(queueTables)) {
                AtomicLong[] values = tableValues.computeIfAbsent(stats.table(), this::registerTable);
                values[0].set(stats.deadTuples());
                values[1].set(stats.liveTuples());
                values[2].set(stats.totalBytes());
                values[3].set(stats.secondsSinceVacuum());
            }
            Set<String> seen = new HashSet<>();
            for (LockWaitStats waits : activity.lockWaits()) {
                AtomicLong[] values = lockValues.computeIfAbsent(waits.waitEvent(), this::registerLock);
                values[0].set(waits.waitingSessions());
                values[1].set(Math.round(waits.oldestWaitSeconds() * 1000));
                seen.add(waits.waitEvent());
            }
            lockValues.forEach((event, values) -> {
                if (!seen.contains(event)) {
                    values[0].set(0);
                    values[1].set(0);
                }
            });
        } catch (RuntimeException exception) {
            meters.counter("atlas.db.signals.refresh_failures").increment();
            log.warn("Database signal refresh failed", exception);
        }
    }

    private AtomicLong[] registerTable(String table) {
        AtomicLong[] values = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
        Tags tags = Tags.of("table", table);
        meters.gauge("atlas.db.queue_table.dead_tuples", tags, values[0], AtomicLong::get);
        meters.gauge("atlas.db.queue_table.live_tuples", tags, values[1], AtomicLong::get);
        meters.gauge("atlas.db.queue_table.total_bytes", tags, values[2], AtomicLong::get);
        meters.gauge("atlas.db.queue_table.seconds_since_vacuum", tags, values[3], AtomicLong::get);
        return values;
    }

    private AtomicLong[] registerLock(String waitEvent) {
        AtomicLong[] values = {new AtomicLong(), new AtomicLong()};
        Tags tags = Tags.of("wait_event", waitEvent);
        meters.gauge("atlas.db.lock_wait.sessions", tags, values[0], AtomicLong::get);
        meters.gauge("atlas.db.lock_wait.oldest_seconds", tags, values[1], value -> value.get() / 1000.0);
        return values;
    }
}
