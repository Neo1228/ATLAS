package com.atlas.settlement.dto.response;

import java.time.Instant;
import java.util.List;

/** 원장 불변식 점검 결과. 점검 이름은 {@code ledger-audit.sql}의 {@code check_name}과 같다. */
public record LedgerAuditResponse(Instant checkedAt, List<Finding> findings) {
    public LedgerAuditResponse {
        findings = List.copyOf(findings);
    }

    public long totalViolations() {
        return findings.stream().mapToLong(Finding::violations).sum();
    }

    public record Finding(String check, long violations) {
    }
}
