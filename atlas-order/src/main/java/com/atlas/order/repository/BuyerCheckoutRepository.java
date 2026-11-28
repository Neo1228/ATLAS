package com.atlas.order.repository;

import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.model.BuyerCheckoutState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
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

    /**
     * Buyer가 결제하지 않고 window를 넘긴 checkout 중 {@code [since, now]}에 만료된 것이 이 cart의 SKU를 잡았던 수량.
     * 만료 sweep 전의 RESERVED도 센다. 취소(CANCELLED)는 재고를 바로 돌려주므로 여기서 세지 않고
     * {@link #findSkuOverCancelledHoldBudget}가 보유 시간 합으로 따로 센다(ADR-0113 결정 6).
     */
    @Query(value = """
            SELECT item.sku_id
            FROM buyer_checkout checkout
            JOIN purchase_order purchase ON purchase.checkout_id = checkout.checkout_id
            JOIN order_item item ON item.order_id = purchase.order_id
            WHERE checkout.buyer_ref = :buyerRef
              AND item.sku_id IN (:skuIds)
              AND checkout.state IN ('RESERVED', 'EXPIRED')
              AND purchase.checkout_expires_at <= :now AND purchase.checkout_expires_at > :since
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findRecentlyExpiredHeldSku(@Param("buyerRef") String buyerRef,
            @Param("skuIds") Collection<String> skuIds, @Param("since") Instant since, @Param("now") Instant now);

    /**
     * {@code since} 뒤에 Buyer가 취소한(CANCELLED) checkout들이 이 cart의 SKU를 잡고 있던 시간의 합이
     * {@code budgetSeconds} 이상인 SKU. CANCELLED는 Buyer 취소 요청으로만 생긴다. 만료는 EXPIRED, 결제 실패는
     * FAILED로 끝나므로 세지 않는다. 취소 시각은 결제·원격 예약이 있어 CANCEL_PENDING을 거쳤으면 그 요청 시각,
     * 곧바로 CANCELLED가 됐으면 마지막 갱신 시각이다.
     */
    @Query(value = """
            SELECT held.sku_id
            FROM (SELECT DISTINCT checkout.checkout_id, item.sku_id,
                         EXTRACT(EPOCH FROM COALESCE(checkout.cancellation_requested_at, checkout.updated_at)
                                 - checkout.created_at) AS held_seconds
                  FROM buyer_checkout checkout
                  JOIN purchase_order purchase ON purchase.checkout_id = checkout.checkout_id
                  JOIN order_item item ON item.order_id = purchase.order_id
                  WHERE checkout.buyer_ref = :buyerRef
                    AND item.sku_id IN (:skuIds)
                    AND checkout.state = 'CANCELLED'
                    AND COALESCE(checkout.cancellation_requested_at, checkout.updated_at) > :since) held
            GROUP BY held.sku_id
            HAVING SUM(held.held_seconds) >= :budgetSeconds
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findSkuOverCancelledHoldBudget(@Param("buyerRef") String buyerRef,
            @Param("skuIds") Collection<String> skuIds, @Param("since") Instant since,
            @Param("budgetSeconds") long budgetSeconds);

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
