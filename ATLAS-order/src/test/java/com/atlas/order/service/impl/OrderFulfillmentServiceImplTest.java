package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderFulfillment;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderRefundRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderFulfillmentServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID ORDER_ID = UUID.randomUUID();

    @Test
    void advancesThroughEachSequentialStateAndRecordsTimestamps() {
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        assertTrue(fulfillment.transitionTo(FulfillmentStatus.IN_PROGRESS, NOW.plusSeconds(1)));
        assertTrue(fulfillment.transitionTo(FulfillmentStatus.SHIPPED, NOW.plusSeconds(2)));
        assertTrue(fulfillment.transitionTo(FulfillmentStatus.DELIVERED, NOW.plusSeconds(3)));
        assertEquals(FulfillmentStatus.DELIVERED, fulfillment.status());
        assertEquals(NOW.plusSeconds(1), fulfillment.inProgressAt());
        assertEquals(NOW.plusSeconds(2), fulfillment.shippedAt());
        assertEquals(NOW.plusSeconds(3), fulfillment.deliveredAt());
    }

    @Test
    void rejectsSkippedOrBackwardTransitionAsConflict() {
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        OrderException exception = assertThrows(OrderException.class,
                () -> fulfillment.transitionTo(FulfillmentStatus.SHIPPED, NOW.plusSeconds(1)));
        assertEquals(OrderException.Code.CONFLICT, exception.code());
        fulfillment.transitionTo(FulfillmentStatus.IN_PROGRESS, NOW.plusSeconds(2));
        OrderException backward = assertThrows(OrderException.class,
                () -> fulfillment.transitionTo(FulfillmentStatus.UNFULFILLED, NOW.plusSeconds(3)));
        assertEquals(OrderException.Code.CONFLICT, backward.code());
    }

    @Test
    void repeatingCurrentStateIsIdempotent() {
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        assertTrue(fulfillment.transitionTo(FulfillmentStatus.IN_PROGRESS, NOW.plusSeconds(1)));
        Instant updated = fulfillment.updatedAt();
        assertFalse(fulfillment.transitionTo(FulfillmentStatus.IN_PROGRESS, NOW.plusSeconds(2)));
        assertEquals(updated, fulfillment.updatedAt());
    }

    @Test
    void initializationIsIdempotentForAnExistingPaidOrder() {
        OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        when(orders.lockById(ORDER_ID)).thenReturn(new PurchaseOrder(UUID.randomUUID(), "buyer",
                "checkout-key", OrderState.PAID, NOW.plusSeconds(600), NOW, NOW));
        OrderFulfillmentServiceImpl service = new OrderFulfillmentServiceImpl(
                orders, fulfillments, refunds, Clock.fixed(NOW, ZoneOffset.UTC));
        when(fulfillments.findById(ORDER_ID)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new OrderFulfillment(ORDER_ID, NOW)));
        service.initialize(ORDER_ID);
        service.initialize(ORDER_ID);
        verify(fulfillments).save(any(OrderFulfillment.class));
        verify(fulfillments, never()).save(argThat(
                fulfillment -> fulfillment.status() != FulfillmentStatus.UNFULFILLED));
    }

    @Test
    void fullyRefundedOrderCannotAdvanceFulfillment() {
        UUID sellerId = UUID.randomUUID();
        OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        when(orders.lockById(ORDER_ID)).thenReturn(new PurchaseOrder(sellerId, "buyer", "checkout-key",
                OrderState.PAID, NOW.plusSeconds(600), NOW, NOW));
        when(fulfillments.findById(ORDER_ID)).thenReturn(Optional.of(fulfillment));
        when(refunds.isFullyRefunded(ORDER_ID)).thenReturn(true);
        OrderFulfillmentServiceImpl service = new OrderFulfillmentServiceImpl(
                orders, fulfillments, refunds, Clock.fixed(NOW, ZoneOffset.UTC));

        OrderException exception = assertThrows(OrderException.class,
                () -> service.updateStatus(sellerId, ORDER_ID, FulfillmentStatus.IN_PROGRESS));

        assertEquals(OrderException.Code.CONFLICT, exception.code());
        verify(fulfillments, never()).save(any(OrderFulfillment.class));
    }
}
