package com.atlas.order.repository;

import com.atlas.order.persistence.entity.BuyerCheckout;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface BuyerCheckoutRepository extends JpaRepository<BuyerCheckout, UUID> {
    Optional<BuyerCheckout> findByBuyerRefAndIdempotencyKey(String buyerRef, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BuyerCheckout> findWithLockByCheckoutId(UUID checkoutId);
}
