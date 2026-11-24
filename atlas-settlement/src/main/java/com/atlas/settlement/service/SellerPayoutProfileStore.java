package com.atlas.settlement.service;

import com.atlas.settlement.model.SellerPayoutProfile;
import com.atlas.settlement.model.SellerPayoutProfileActionType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 판매자 지급 프로필의 저장과 검증 이력을 계약으로 노출합니다. SQL은 SellerPayoutProfileRepository에만 둔다. */
public interface SellerPayoutProfileStore {

    Optional<SellerPayoutProfile> find(UUID sellerId);

    SellerPayoutProfile submit(UUID sellerId, UUID accountId, String countryCode, Instant now);

    Optional<SellerPayoutProfile> verify(UUID sellerId, int expectedVersion, String actor, Instant now);

    Optional<String> findRequestHash(UUID sellerId, String idempotencyKey);

    boolean recordAction(UUID sellerId, int profileVersion, SellerPayoutProfileActionType action, String idempotencyKey,
            String requestHash, String actor, String reason, Instant now);
}
