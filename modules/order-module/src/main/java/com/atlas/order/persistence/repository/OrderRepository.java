package com.atlas.order.persistence.repository;

import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.persistence.entity.PurchaseOrder;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PurchaseOrder> findWithLockByOrderId(UUID orderId);

    @Query("select po.orderId from PurchaseOrder po where po.state = :state "
            + "and po.checkoutExpiresAt <= :expiresAt order by po.checkoutExpiresAt, po.orderId")
    List<UUID> findExpiredOrderIds(@Param("state") OrderState state, @Param("expiresAt") Instant expiresAt);

    boolean existsByCampaignIdAndBuyerRefAndOrderIdNotAndStateIn(
            UUID campaignId, String buyerRef, UUID orderId, List<OrderState> states);

    default PurchaseOrder requireById(UUID orderId) {
        return findById(orderId).orElseThrow(() ->
                new OrderException(OrderException.Code.NOT_FOUND, "Unknown order: " + orderId));
    }

    default PurchaseOrder lockById(UUID orderId) {
        return findWithLockByOrderId(orderId).orElseThrow(() ->
                new OrderException(OrderException.Code.NOT_FOUND, "Unknown order: " + orderId));
    }

    default PurchaseOrder findByTicketOrNull(UUID ticketId) {
        return findByTicketId(ticketId).orElse(null);
    }

    default boolean hasOtherUnresolvedPayment(PurchaseOrder order) {
        return existsByCampaignIdAndBuyerRefAndOrderIdNotAndStateIn(order.campaignId(), order.buyerRef(),
                order.orderId(), UNRESOLVED_STATES);
    }

    default void updateState(UUID orderId, OrderState state, UUID paymentAttemptId, Instant updatedAt) {
        PurchaseOrder order = lockById(orderId);
        order.updateState(state, paymentAttemptId, updatedAt);
    }

    default List<UUID> findReservedOrdersExpiredBy(Instant now) {
        return findExpiredOrderIds(OrderState.RESERVED, now);
    }
}
