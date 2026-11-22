package com.atlas.account.domain;

import java.util.Objects;
import java.util.UUID;

/** Active Seller account identity with a verified email, exposed to trusted application orchestration. */
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
