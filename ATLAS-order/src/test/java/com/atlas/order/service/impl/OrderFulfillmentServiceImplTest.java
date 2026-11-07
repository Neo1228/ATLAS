package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
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
    void summaryTracksPartialDispatchThenFullDispatchAndDelivery() {
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        assertTrue(fulfillment.start(NOW.plusSeconds(1)));
        assertFalse(fulfillment.start(NOW.plusSeconds(2)));
        assertTrue(fulfillment.refreshSummary(4, 2, 0, NOW.plusSeconds(3)));
        assertEquals(FulfillmentStatus.PARTIALLY_SHIPPED, fulfillment.status());
        assertEquals(NOW.plusSeconds(1), fulfillment.inProgressAt());
        assertEquals(null, fulfillment.shippedAt());
        assertFalse(fulfillment.refreshSummary(4, 2, 1, NOW.plusSeconds(4)));
        assertEquals(FulfillmentStatus.PARTIALLY_SHIPPED, fulfillment.status());
        assertEquals(NOW.plusSeconds(4), fulfillment.updatedAt());
        assertTrue(fulfillment.refreshSummary(4, 4, 2, NOW.plusSeconds(5)));
        assertEquals(FulfillmentStatus.SHIPPED, fulfillment.status());
        assertEquals(NOW.plusSeconds(5), fulfillment.shippedAt());
        assertEquals(null, fulfillment.deliveredAt());
        assertTrue(fulfillment.refreshSummary(4, 4, 4, NOW.plusSeconds(6)));
        assertEquals(FulfillmentStatus.DELIVERED, fulfillment.status());
        assertEquals(NOW.plusSeconds(6), fulfillment.deliveredAt());
    }


    @Test
    void handlesMultiItemTotalsAboveIntegerRange() {
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        fulfillment.start(NOW.plusSeconds(1));

        assertTrue(fulfillment.refreshSummary(3_000_000_000L, 1_500_000_000L, 0, NOW.plusSeconds(2)));
        assertEquals(FulfillmentStatus.PARTIALLY_SHIPPED, fulfillment.status());
    }

    @Test
    void doesNotClaimDeliveredWhenNoQuantityIsShippable() {
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        fulfillment.start(NOW.plusSeconds(1));
        assertFalse(fulfillment.refreshSummary(0, 0, 0, NOW.plusSeconds(2)));
        assertEquals(FulfillmentStatus.IN_PROGRESS, fulfillment.status());
        assertEquals(null, fulfillment.shippedAt());
        assertEquals(null, fulfillment.deliveredAt());
    }

    @Test
    void rejectsInvalidTotalsAndInvalidStartProgression() {
        OrderFulfillment fulfillment = new OrderFulfillment(ORDER_ID, NOW);
        assertThrows(IllegalStateException.class,
                () -> fulfillment.refreshSummary(3, 4, 0, NOW.plusSeconds(1)));
        fulfillment.start(NOW.plusSeconds(2));
        fulfillment.refreshSummary(3, 3, 0, NOW.plusSeconds(3));
        assertThrows(OrderException.class, () -> fulfillment.start(NOW.plusSeconds(4)));
    }

    @Test
    void initializeIsIdempotent() {
        OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        PurchaseOrder order = paidOrder();
        when(orders.findWithLockByOrderId(ORDER_ID)).thenReturn(Optional.of(order));
        when(fulfillments.findById(ORDER_ID)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new OrderFulfillment(ORDER_ID, NOW)));
        OrderFulfillmentServiceImpl service = new OrderFulfillmentServiceImpl(orders, fulfillments, refunds,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.initialize(ORDER_ID);
        service.initialize(ORDER_ID);

        verify(fulfillments).save(any(OrderFulfillment.class));
    }

    @Test
    void startRequiresPaidSellerOwnershipAndNotFullyRefunded() {
        OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        UUID sellerId = UUID.randomUUID();
        PurchaseOrder order = paidOrder();
        when(order.sellerId()).thenReturn(sellerId);
        when(orders.findWithLockByOrderId(ORDER_ID)).thenReturn(Optional.of(order));
        when(fulfillments.findById(ORDER_ID)).thenReturn(Optional.of(new OrderFulfillment(ORDER_ID, NOW)));
        when(refunds.isFullyRefunded(ORDER_ID)).thenReturn(true);
        OrderFulfillmentServiceImpl service = new OrderFulfillmentServiceImpl(orders, fulfillments, refunds,
                Clock.fixed(NOW, ZoneOffset.UTC));

        OrderException exception = assertThrows(OrderException.class, () -> service.start(sellerId, ORDER_ID));
        assertEquals(OrderException.Code.CONFLICT, exception.code());
        verify(fulfillments, never()).save(any(OrderFulfillment.class));
    }

    private static PurchaseOrder paidOrder() {
        PurchaseOrder order = mock(PurchaseOrder.class);
        when(order.state()).thenReturn(OrderState.PAID);
        when(order.orderId()).thenReturn(ORDER_ID);
        when(order.sellerId()).thenReturn(UUID.randomUUID());
        return order;
    }
}
