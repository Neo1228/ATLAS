package com.atlas.order.repository;

import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuyerCheckoutRepository extends JpaRepository<BuyerCheckout, UUID> {
    Optional<BuyerCheckout> findByBuyerRefAndIdempotencyKey(String buyerRef, String idempotencyKey);

    List<BuyerCheckout> findByStateOrderByCreatedAtAsc(BuyerCheckoutState state, Limit limit);

    @Query("select c from BuyerCheckout c where c.state = :state and exists "
            + "(select o.orderId from PurchaseOrder o where o.checkoutId = c.checkoutId "
            + "and o.reservationWorkflowId is not null) order by c.createdAt")
    List<BuyerCheckout> findRemoteByState(@Param("state") BuyerCheckoutState state, Limit limit);
    @Query("""
            select min(checkout.createdAt)
            from BuyerCheckout checkout
            where checkout.state = :state
              and exists (
                  select purchase.orderId from PurchaseOrder purchase
                  where purchase.checkoutId = checkout.checkoutId
                    and purchase.reservationWorkflowId is not null
              )
            """)
    Instant findOldestRemoteCreatedAtByState(@Param("state") BuyerCheckoutState state);



    @Query(value = """
            SELECT MIN(checkout.cancellation_requested_at)
            FROM buyer_checkout checkout
            WHERE checkout.state = 'CANCEL_PENDING'
              AND checkout.cancellation_requested_at IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM purchase_order purchase
                  WHERE purchase.checkout_id = checkout.checkout_id
                    AND purchase.reservation_workflow_id IS NOT NULL
              )
            """, nativeQuery = true)
    Instant findOldestRemoteCancellationRequestedAt();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BuyerCheckout> findWithLockByCheckoutId(UUID checkoutId);
}
