package com.atlas.cart.repository;

import com.atlas.cart.persistence.entity.Cart;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, UUID> {
    Optional<Cart> findByBuyerRef(String buyerRef);
    @Modifying
    @Query(value = """
            INSERT INTO shopping_cart (cart_id, buyer_ref)
            VALUES (uuidv7(), :buyerRef)
            ON CONFLICT (buyer_ref) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsentByBuyerRef(@Param("buyerRef") String buyerRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findWithLockByBuyerRef(String buyerRef);
}
