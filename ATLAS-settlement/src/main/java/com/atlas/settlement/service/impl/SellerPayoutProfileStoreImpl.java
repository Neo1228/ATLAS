package com.atlas.settlement.service.impl;

import com.atlas.settlement.repository.SellerPayoutProfileRepository;
import com.atlas.settlement.service.SellerPayoutProfile;
import com.atlas.settlement.service.SellerPayoutProfileStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class SellerPayoutProfileStoreImpl implements SellerPayoutProfileStore {
    private final SellerPayoutProfileRepository repository;

    public SellerPayoutProfileStoreImpl(SellerPayoutProfileRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<SellerPayoutProfile> find(UUID sellerId) {
        return repository.find(sellerId);
    }

    @Override
    public SellerPayoutProfile submit(UUID sellerId, UUID accountId, String countryCode, Instant now) {
        return repository.submit(sellerId, accountId, countryCode, now);
    }

    @Override
    public Optional<SellerPayoutProfile> verify(UUID sellerId, int expectedVersion, String actor, Instant now) {
        return repository.verify(sellerId, expectedVersion, actor, now);
    }

    @Override
    public Optional<String> findRequestHash(UUID sellerId, String idempotencyKey) {
        return repository.findRequestHash(sellerId, idempotencyKey);
    }

    @Override
    public boolean recordAction(UUID sellerId, int profileVersion, String action, String idempotencyKey, String requestHash, String actor, String reason, Instant now) {
        return repository.recordAction(sellerId, profileVersion, action, idempotencyKey, requestHash, actor, reason, now);
    }
}
