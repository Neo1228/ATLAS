package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.order.dto.request.OrderShipmentRequest;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderFulfillment;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.OrderShipment;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderShipmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderShipmentServiceImplTest {
    @Test
    void malformedRequestReturnsTypedInvalidRequestBeforeDatabaseAccess() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        OrderShipmentRepository shipments = mock(OrderShipmentRepository.class);
        OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
        OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        OrderShipmentServiceImpl service = new OrderShipmentServiceImpl(orders, items, shipments, fulfillments,
                refunds, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        UUID seller = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID item = UUID.randomUUID();

        OrderException failure = assertThrows(OrderException.class, () -> service.create(new OrderShipmentRequest(
                seller, order, " key ", null, null,
                List.of(new OrderShipmentRequest.Item(item, 1), new OrderShipmentRequest.Item(item, 2)))));

        assertEquals(OrderException.Code.INVALID_REQUEST, failure.code());
        verify(orders, never()).findWithLockByOrderId(order);
    }

    @Test
    void nullRequestItemsAreReportedAsTypedValidationFailure() {
        OrderShipmentServiceImpl service = new OrderShipmentServiceImpl(mock(OrderRepository.class),
                mock(OrderItemRepository.class), mock(OrderShipmentRepository.class),
                mock(OrderFulfillmentRepository.class), mock(OrderRefundRepository.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        OrderShipmentRequest request = new OrderShipmentRequest(UUID.randomUUID(), UUID.randomUUID(), "key",
                null, null, null);

        OrderException failure = assertThrows(OrderException.class, () -> service.create(request));
        assertEquals(OrderException.Code.INVALID_REQUEST, failure.code());
    }
    @Test
    void rejectsOrderItemOwnedByDifferentSeller() {
        UUID sellerId = UUID.randomUUID();
        UUID otherSellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();

        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository orderItems = mock(OrderItemRepository.class);
        OrderShipmentRepository shipments = mock(OrderShipmentRepository.class);
        OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
        OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        PurchaseOrder order = mock(PurchaseOrder.class);
        when(order.sellerId()).thenReturn(sellerId);
        when(order.state()).thenReturn(OrderState.PAID);
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));

        OrderFulfillment fulfillment = new OrderFulfillment(orderId, Instant.EPOCH);
        fulfillment.start(Instant.EPOCH.plusSeconds(1));
        when(fulfillments.findById(orderId)).thenReturn(Optional.of(fulfillment));
        OrderItem foreignItem = mock(OrderItem.class);
        when(foreignItem.orderItemId()).thenReturn(orderItemId);
        when(foreignItem.sellerId()).thenReturn(otherSellerId);
        when(orderItems.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of(foreignItem));
        when(shipments.findByOrderOrderIdAndIdempotencyKey(orderId, "foreign-item"))
                .thenReturn(Optional.empty());

        OrderShipmentServiceImpl service = new OrderShipmentServiceImpl(
                orders, orderItems, shipments, fulfillments, refunds,
                Clock.fixed(Instant.EPOCH.plusSeconds(2), ZoneOffset.UTC));
        OrderShipmentRequest request = new OrderShipmentRequest(sellerId, orderId, "foreign-item", null, null,
                List.of(new OrderShipmentRequest.Item(orderItemId, 1)));

        OrderException failure = assertThrows(OrderException.class, () -> service.create(request));

        assertEquals(OrderException.Code.INVALID_REQUEST, failure.code());
        verify(shipments, never()).save(any(OrderShipment.class));
    }
}
