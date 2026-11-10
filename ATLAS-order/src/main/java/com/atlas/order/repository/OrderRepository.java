package com.atlas.order.repository;

import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<PurchaseOrder, UUID> {
    List<OrderState> UNRESOLVED_STATES = List.of(OrderState.PAYMENT_SUBMITTED, OrderState.PAYMENT_PENDING,
            OrderState.PAYMENT_UNKNOWN, OrderState.PAYMENT_VOID_PENDING, OrderState.CANCEL_PENDING);

    Optional<PurchaseOrder> findByTicketId(UUID ticketId);
    boolean existsByOrderIdAndBuyerRef(UUID orderId, String buyerRef);

    Optional<PurchaseOrder> findBySellerIdAndBuyerRefAndCheckoutKey(UUID sellerId, String buyerRef,
                                                                    String checkoutKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PurchaseOrder> findWithLockByOrderId(UUID orderId);

    @Query("select po.orderId from PurchaseOrder po where po.checkoutId is null and po.state = :state "
            + "and po.checkoutExpiresAt <= :now order by po.checkoutExpiresAt, po.orderId")
    List<UUID> findExpiredOrderIds(@Param("state") OrderState state, @Param("now") Instant now);

    @Query("select distinct po.checkoutId from PurchaseOrder po where po.checkoutId is not null "
            + "and po.state = :state and po.checkoutExpiresAt <= :now order by po.checkoutId")
    List<UUID> findExpiredCheckoutIds(@Param("state") OrderState state, @Param("now") Instant now);


    default boolean hasOtherUnresolvedPayment(PurchaseOrder order) {
        return existsByCampaignIdAndBuyerRefAndOrderIdNotAndStateIn(order.campaignId(), order.buyerRef(),
                order.orderId(), UNRESOLVED_STATES);
    }

    boolean existsByCampaignIdAndBuyerRefAndOrderIdNotAndStateIn(UUID campaignId, String buyerRef,
                                                                 UUID orderId, List<OrderState> states);

    default List<UUID> findReservedOrdersExpiredBy(Instant now) {
        return findExpiredOrderIds(OrderState.RESERVED, now);
    }
    List<PurchaseOrder> findAllByCheckoutIdOrderBySellerIdAsc(UUID checkoutId);
}
