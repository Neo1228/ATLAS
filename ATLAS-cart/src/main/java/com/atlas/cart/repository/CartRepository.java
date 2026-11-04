package com.atlas.cart.repository;

import com.atlas.cart.persistence.entity.Cart;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CartRepository extends JpaRepository<Cart, UUID> {
    Optional<Cart> findBySellerIdAndBuyerRef(UUID sellerId, String buyerRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findWithLockBySellerIdAndBuyerRef(UUID sellerId, String buyerRef);
}
