package com.atlas.order.repository;

import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.model.OrderState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<PurchaseOrder, UUID> {
    List<OrderState> UNRESOLVED_STATES = List.of(OrderState.PAYMENT_SUBMITTED, OrderState.PAYMENT_PENDING,
            OrderState.PAYMENT_UNKNOWN, OrderState.PAYMENT_VOID_PENDING, OrderState.CANCEL_PENDING);

    Optional<PurchaseOrder> findByTicketId(UUID ticketId);
    boolean existsByOrderIdAndBuyerRef(UUID orderId, String buyerRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PurchaseOrder> findWithLockByOrderId(UUID orderId);

    @Query("select po.orderId from PurchaseOrder po where po.checkoutId is null and po.state = :state "
            + "and po.checkoutExpiresAt <= :now order by po.checkoutExpiresAt, po.orderId")
    List<UUID> findExpiredOrderIds(@Param("state") OrderState state, @Param("now") Instant now,
            Limit limit);

    /**
     * 만료된 checkout 후보를 조회합니다.
     *
     * <p>{@code ORDER BY checkout_id} 를 쓰지 않습니다. 그 정렬은
     * {@code (checkout_id, seller_id)} 인덱스로 만족되지만, 그 인덱스로는
     * {@code state}·{@code checkout_expires_at} 필터를 걸러낼 수 없습니다.
     * 플래너는 정렬 비용 0 을 이유로 그 계획을 고르고, 결과적으로 LIMIT 이
     * 조기 종료되지 못해 {@code purchase_order} 전체를 훑습니다.
     *
     * <p>실측(로컬, 10,934행 중 RESERVED 810행): 기존 계획은 buffer 9,960 /
     * 4.7–23 ms, {@code purchase_order_expiry_idx} 를 쓰는 아래 형태는
     * buffer 16 / 0.376 ms 입니다. 같은 조건의 주문 sweep 과 같은 정렬 키를
     * 써서 두 sweep 이 같은 부분 인덱스를 공유하게 합니다.
     *
     * <p>정렬은 {@code checkout_expires_at} 다음에 {@code checkout_id} 를 둡니다.
     * 만료 시각만으로 정렬하면 같은 시각의 후보 순서가 비결정적이 되는데,
     * 다중 인스턴스 만료 테스트가 특정 checkout 이 먼저 처리되는 것에 의존합니다.
     * {@code checkout_id} 를 타이브레이커로 두면 순서가 확정적이면서도 첫 키가
     * 부분 인덱스와 일치해 incremental sort 로 처리됩니다(실측 buffer 21).
     *
     * <p>정렬 키가 select 목록에 있어야 하므로 {@code distinct} 는 두지 않습니다
     * (PostgreSQL 은 {@code SELECT DISTINCT} 와 그 밖의 정렬 표현식을 함께 허용하지
     * 않습니다). 한 checkout 의 주문들은 만료 시각이 같아 중복이 생길 수 있고,
     * 호출자가 같은 checkout 의 두 번째 처리에서 {@code state} 를 다시 확인해
     * 0 을 반환하므로 중복은 무해합니다. 배치 크기는 중복을 포함해 제한됩니다.
     */
    @Query("select po.checkoutId from PurchaseOrder po where po.checkoutId is not null "
            + "and po.state = :state and po.checkoutExpiresAt <= :now order by po.checkoutExpiresAt, po.checkoutId")
    List<UUID> findExpiredCheckoutIds(@Param("state") OrderState state, @Param("now") Instant now,
            Limit limit);

    default boolean hasOtherUnresolvedPayment(PurchaseOrder order) {
        return existsByCampaignIdAndBuyerRefAndOrderIdNotAndStateIn(order.campaignId(), order.buyerRef(),
                order.orderId(), UNRESOLVED_STATES);
    }

    boolean existsByCampaignIdAndBuyerRefAndOrderIdNotAndStateIn(UUID campaignId, String buyerRef,
                                                                 UUID orderId, List<OrderState> states);

    default List<UUID> findReservedOrdersExpiredBy(Instant now, Limit limit) {
        return findExpiredOrderIds(OrderState.RESERVED, now, limit);
    }
    List<PurchaseOrder> findAllByCheckoutIdOrderBySellerIdAsc(UUID checkoutId);
    List<PurchaseOrder> findByBuyerRefOrderByCreatedAtDescOrderIdDesc(String buyerRef, Pageable pageable);

    /** 판매자 주문 목록 첫 페이지. keyset 이므로 COUNT/OFFSET 을 쓰지 않는다. */
    List<PurchaseOrder> findBySellerIdOrderByCreatedAtDescOrderIdDesc(UUID sellerId, Pageable pageable);

    /** 판매자 주문 목록 다음 페이지. (created_at, order_id) 튜플 비교로 keyset 을 구현한다. */
    @Query("""
            select purchaseOrder from PurchaseOrder purchaseOrder
            where purchaseOrder.sellerId = :sellerId
                and (purchaseOrder.createdAt < :createdAt
                    or (purchaseOrder.createdAt = :createdAt and purchaseOrder.orderId < :orderId))
            order by purchaseOrder.createdAt desc, purchaseOrder.orderId desc
            """)
    List<PurchaseOrder> findSellerOrdersBefore(@Param("sellerId") UUID sellerId,
            @Param("createdAt") Instant createdAt, @Param("orderId") UUID orderId, Pageable pageable);

    @Query("""
            select purchaseOrder from PurchaseOrder purchaseOrder
            where purchaseOrder.buyerRef = :buyerRef
                and (purchaseOrder.createdAt < :createdAt
                    or (purchaseOrder.createdAt = :createdAt and purchaseOrder.orderId < :orderId))
            order by purchaseOrder.createdAt desc, purchaseOrder.orderId desc
            """)
    List<PurchaseOrder> findBuyerOrdersBefore(@Param("buyerRef") String buyerRef,
            @Param("createdAt") Instant createdAt, @Param("orderId") UUID orderId, Pageable pageable);
    @Query(value = """
            SELECT item.order_item_id
            FROM purchase_order buyer_order
            JOIN order_item item ON item.order_id = buyer_order.order_id
            JOIN order_shipment_item shipment_item
              ON shipment_item.order_id = item.order_id
             AND shipment_item.order_item_id = item.order_item_id
            JOIN order_shipment shipment
              ON shipment.shipment_id = shipment_item.shipment_id
             AND shipment.order_id = shipment_item.order_id
            WHERE buyer_order.buyer_ref = :buyerRef
              AND item.product_id = :productId
              AND shipment.status = 'DELIVERED'
              AND shipment_item.quantity > 0
            ORDER BY shipment.delivered_at DESC, shipment.shipment_id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findDeliveredOrderItemId(@Param("buyerRef") String buyerRef,
            @Param("productId") UUID productId);
}
