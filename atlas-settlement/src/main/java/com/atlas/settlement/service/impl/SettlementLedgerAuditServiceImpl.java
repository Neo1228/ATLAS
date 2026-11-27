package com.atlas.settlement.service.impl;

import com.atlas.settlement.dto.response.LedgerAuditResponse;
import com.atlas.settlement.repository.SettlementLedgerAuditRepository;
import com.atlas.settlement.service.SettlementLedgerAuditService;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class SettlementLedgerAuditServiceImpl implements SettlementLedgerAuditService {
    private final SettlementLedgerAuditRepository audits;
    private final Clock clock;

    public SettlementLedgerAuditServiceImpl(SettlementLedgerAuditRepository audits, Clock clock) {
        this.audits = Objects.requireNonNull(audits);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public LedgerAuditResponse audit() {
        var findings = audits.audit().stream()
                .map(finding -> new LedgerAuditResponse.Finding(finding.checkName(), finding.violations()))
                .toList();
        return new LedgerAuditResponse(clock.instant(), findings);
    }
}
