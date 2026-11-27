package com.atlas.settlement.persistence.projection;

/** 원장 불변식 점검 한 건의 결과. {@code violations}가 0이면 해당 불변식이 지켜진 것이다. */
public record LedgerAuditFinding(String checkName, long violations) {
}
