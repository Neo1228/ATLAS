package com.atlas.account.dto.response;

import com.atlas.account.model.AccountNotificationOutcome;
import com.atlas.account.model.AccountSuspensionActionType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccountResponse {
    private AccountResponse() {
    }

    public record Action(boolean accepted) {
    }

    public record Login(boolean mfaRequired, Token tokens, String challengeToken) {
    }

    /** 제한된 계정 session 목록과 다음 항목 존재 여부입니다. */
    public record SessionPage(List<Session> items, int limit, int offset, boolean hasMore) {
        public SessionPage {
            items = List.copyOf(items);
        }
    }

    /** 현재 계정의 refresh family 상태를 토큰 비밀 없이 보여줍니다. */
    public record Session(UUID familyId, Instant createdAt, Instant expiresAt, boolean current) {
    }

    /**
     * 계정 정지·해제 감사 기록 한 건의 응답입니다.
     *
     * <p>컨트롤러가 리포지토리 타입을 그대로 노출하면 계층 경계를 넘으므로 응답 DTO 로
     * 옮깁니다({@code ModuleBoundaryTest} 규칙).
     */
    public record SuspensionAction(
            AccountSuspensionActionType action,
            String idempotencyKey,
            String actor,
            String reason,
            Instant createdAt) {
    }

    public record Token(String accessToken, String refreshToken, String tokenType,
                                       long expiresInSeconds, UUID accountId, String buyerRef,
                                       List<UUID> sellerIds) {
        public Token {
            sellerIds = List.copyOf(sellerIds);
        }
    }

    public record MfaEnrollment(String secret, String otpauthUri) {
    }

    public record MfaRecoveryCodes(List<String> recoveryCodes) {
    }

    /** 이메일이 검증된 활성 Seller 계정의 식별 정보. 신뢰하는 애플리케이션 orchestration에만 노출한다. */
    public record VerifiedSellerAccount(UUID accountId, String email) {
        public VerifiedSellerAccount {
            Objects.requireNonNull(accountId);
            if (email == null || email.isBlank()) throw new IllegalArgumentException("Verified email is required");
        }

        @Override
        public String toString() {
            return "VerifiedSellerAccount[accountId=" + accountId + ", email=[REDACTED]]";
        }
    }

    /** 알림 outbox 행 하나의 처리 결과. {@code sinceRequested}는 요청 커밋부터 이 처리까지의 시간이다. */
    public record NotificationDelivery(AccountNotificationOutcome outcome, Duration sinceRequested) {
        public NotificationDelivery {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(sinceRequested);
        }
    }

    /** 알림 outbox 적체. 대기 행이 없으면 {@code oldestPendingAge}는 0이다. */
    public record NotificationBacklog(long pending, long failed, Duration oldestPendingAge) {
    }
}
