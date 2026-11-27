package com.atlas.application.service.ledger;

import com.atlas.settlement.dto.response.LedgerAuditResponse;
import com.atlas.settlement.service.SettlementLedgerAuditService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 원장 불변식 점검을 주기적으로 돌려 점검별 위반 건수를 게이지로 노출한다(탐지 전용, 기본 꺼짐).
 *
 * <p>점검은 원장 전체를 읽으므로 켜기 전에 운영 크기에서 걸리는 시간을 재고 간격을 정한다. 점검 연결에는
 * 데이터소스의 {@code statement_timeout}이 적용되어 한도를 넘으면 실패 계수기만 오른다. 게이지에는 판매자·
 * 분개 라벨을 붙이지 않는다(점검 이름 9개가 전부).
 */
@Component
@ConditionalOnProperty(prefix = "atlas.settlement.ledger-audit", name = "enabled", havingValue = "true")
public class LedgerAuditMetrics {
    private static final Logger log = LoggerFactory.getLogger(LedgerAuditMetrics.class);

    private final SettlementLedgerAuditService audits;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> violations = new ConcurrentHashMap<>();
    private final AtomicLong lastRunEpochSeconds = new AtomicLong();

    public LedgerAuditMetrics(SettlementLedgerAuditService audits, MeterRegistry meters) {
        this.audits = Objects.requireNonNull(audits);
        this.meters = Objects.requireNonNull(meters);
        meters.gauge("settlement.ledger.audit.lastrunepochseconds", lastRunEpochSeconds, AtomicLong::get);
    }

    @Scheduled(fixedDelayString = "${atlas.settlement.ledger-audit.poll-interval:PT15M}",
            initialDelayString = "${atlas.settlement.ledger-audit.initial-delay:PT1M}")
    public void refresh() {
        try {
            LedgerAuditResponse report = audits.audit();
            for (LedgerAuditResponse.Finding finding : report.findings()) {
                violations.computeIfAbsent(finding.check(), this::register).set(finding.violations());
                if (finding.violations() > 0) {
                    log.error("Ledger audit violation check={} count={}", finding.check(), finding.violations());
                }
            }
            lastRunEpochSeconds.set(report.checkedAt().getEpochSecond());
        } catch (RuntimeException exception) {
            meters.counter("settlement.ledger.audit.refreshfailures").increment();
            log.warn("Ledger audit failed", exception);
        }
    }

    private AtomicLong register(String check) {
        AtomicLong value = new AtomicLong();
        meters.gauge("settlement.ledger.audit.violations", Tags.of("check", check), value, AtomicLong::get);
        return value;
    }
}
