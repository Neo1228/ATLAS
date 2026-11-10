package com.atlas.account.domain;

import java.util.List;
import java.util.UUID;

public record AccountPrincipal(UUID accountId, String email, UUID buyerId, List<UUID> sellerIds) {
    public AccountPrincipal {
        sellerIds = List.copyOf(sellerIds);
    }

    public String buyerRef() {
        return buyerId == null ? null : buyerId.toString();
    }
}
