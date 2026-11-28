package com.atlas.order.repository;

import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.model.BuyerCheckoutState;
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
    /** Buyer가 지금 재고를 잡고 있는 미결 checkout 수와 그 checkout들이 예약한 수량 합. */
    interface OpenHold {
        long getOpenCheckouts();
        long getReservedUnits();
    }

    /**
     * 결제 전이거나 결제·취소가 끝나지 않아 재고를 잡고 있는 Buyer checkout을 센다.
     * RESERVED는 child order의 checkout window가 지나면 만료 sweep 전이라도 결제할 수 없으므로 세지 않는다.
     * RESERVATION_PENDING은 원격 예약이 이미 잡혔을 수 있으므로 시간과 관계없이 센다.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT checkout.checkout_id) AS "openCheckouts",
                   COALESCE(SUM(item.quantity), 0) AS "reservedUnits"
            FROM buyer_checkout checkout
            JOIN purchase_order purchase ON purchase.checkout_id = checkout.checkout_id
            JOIN order_item item ON item.order_id = purchase.order_id
            WHERE checkout.buyer_ref = :buyerRef
              AND (checkout.state IN ('RESERVATION_PENDING', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                                      'INVENTORY_CLAIM_PENDING', 'CANCEL_PENDING')
                   OR (checkout.state = 'RESERVED' AND purchase.checkout_expires_at > :now))
            """, nativeQuery = true)
    OpenHold findOpenHold(@Param("buyerRef") String buyerRef, @Param("now") Instant now);

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
