package com.atlas.account.dto.response;

import java.util.List;
import java.util.UUID;

public record AccountTokenResponse(String accessToken, String refreshToken, String tokenType,
                                   long expiresInSeconds, UUID accountId, String buyerRef,
                                   List<UUID> sellerIds) {
    public AccountTokenResponse {
        sellerIds = List.copyOf(sellerIds);
    }
}
