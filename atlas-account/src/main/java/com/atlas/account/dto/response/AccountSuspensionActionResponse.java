package com.atlas.account.dto.response;

import java.time.Instant;

/**
 * 계정 정지·해제 감사 기록 한 건의 응답입니다.
 *
 * <p>컨트롤러가 리포지토리 타입을 그대로 노출하면 계층 경계를 넘으므로 응답 DTO 로
 * 옮깁니다({@code ModuleBoundaryTest} 규칙).
 */
public record AccountSuspensionActionResponse(
        String action,
        String idempotencyKey,
        String actor,
        String reason,
        Instant createdAt) {
}
