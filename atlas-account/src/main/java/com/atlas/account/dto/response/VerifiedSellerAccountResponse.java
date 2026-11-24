package com.atlas.account.dto.response;

import java.util.Objects;
import java.util.UUID;

/** 이메일이 검증된 활성 Seller 계정의 식별 정보. 신뢰하는 애플리케이션 orchestration에만 노출한다. */
public record VerifiedSellerAccountResponse(UUID accountId, String email) {
    public VerifiedSellerAccountResponse {
        Objects.requireNonNull(accountId);
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Verified email is required");
    }

    @Override
    public String toString() {
        return "VerifiedSellerAccountResponse[accountId=" + accountId + ", email=[REDACTED]]";
    }
}
