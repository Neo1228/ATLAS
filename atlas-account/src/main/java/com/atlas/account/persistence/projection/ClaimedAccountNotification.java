package com.atlas.account.persistence.projection;

import com.atlas.account.model.AccountActionTokenPurpose;
import java.time.Instant;
import java.util.UUID;

/**
 * 임대를 잡은 알림 outbox 행과 전송 시점의 계정 상태.
 *
 * <p>수신 주소와 확인 여부는 outbox가 아니라 {@code user_account}에서 읽은 현재 값이다.
 */
public record ClaimedAccountNotification(UUID accountId, AccountActionTokenPurpose purpose, UUID leaseOwner,
        int attempts, Instant requestedAt, String email, boolean active, boolean emailVerified) {
    @Override
    public String toString() {
        return "ClaimedAccountNotification[accountId=" + accountId + ", purpose=" + purpose + ", attempts=" + attempts
                + ", email=[REDACTED]]";
    }
}
