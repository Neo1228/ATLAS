package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.projection.RefundItem;
import com.atlas.order.persistence.projection.RefundRecord;
import com.atlas.order.persistence.state.OrderRefundStatus;
import com.atlas.order.persistence.state.OrderRefundPaymentStatus;
import com.atlas.order.persistence.state.OrderReturnInspectionResult;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderReturnRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.persistence.entity.OrderFulfillment;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.OrderReturn;
import com.atlas.order.persistence.entity.OrderReturnItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderReturnStatus;
import com.atlas.order.persistence.state.OrderState;

import com.atlas.settlement.service.SettlementService;
import com.atlas.payment.service.PaymentService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OrderRefundServiceImplTest {
    @Test
    void replaysRefundWhenDatabaseUuidOrderDiffersFromJavaNaturalOrder() {
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID javaFirst = UUID.fromString("00000000-0000-0000-8000-000000000000");
        UUID javaSecond = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");
        String idempotencyKey = "refund-replay-key";
        OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        OrderRepository orders = mock(OrderRepository.class);
        PurchaseOrder order = mock(PurchaseOrder.class);
        when(order.sellerId()).thenReturn(sellerId);
        when(order.state()).thenReturn(OrderState.PAID);
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
        OrderFulfillment fulfillment = mock(OrderFulfillment.class);
        when(fulfillment.status()).thenReturn(FulfillmentStatus.UNFULFILLED);
        when(fulfillments.findById(orderId)).thenReturn(Optional.of(fulfillment));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(refunds.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(new RefundRecord(
                refundId, orderId, sellerId, idempotencyKey, 200, OrderRefundStatus.PARTIALLY_RECORDED,
                Instant.parse("2026-01-01T00:00:00Z"), List.of(new RefundItem(javaSecond, 1),
                new RefundItem(javaFirst, 1)))));
        when(refunds.paymentStatus(refundId)).thenReturn(OrderRefundPaymentStatus.NOT_APPLICABLE);
        OrderRefundServiceImpl service = new OrderRefundServiceImpl(transactions, orders,
                mock(OrderItemRepository.class), fulfillments, refunds,
                mock(OrderReturnRepository.class), mock(InventoryService.class), mock(SettlementService.class),
                mock(PaymentService.class), Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));

        OrderRefundResponse response = service.refund(new OrderRefundRequest(sellerId, orderId, idempotencyKey,
                List.of(new OrderRefundRequest.Item(javaSecond, 1), new OrderRefundRequest.Item(javaFirst, 1))));

        assertEquals(refundId, response.refundId());
    }

    @Test
    void rejectsNullRequestAsInvalidRequest() {
        OrderRefundServiceImpl service = new OrderRefundServiceImpl(
                mock(PlatformTransactionManager.class), mock(OrderRepository.class), mock(OrderItemRepository.class),
                mock(OrderFulfillmentRepository.class), mock(OrderRefundRepository.class),
                mock(OrderReturnRepository.class), mock(InventoryService.class),
                mock(SettlementService.class), mock(PaymentService.class), Clock.systemUTC());

        OrderException exception = assertThrows(OrderException.class, () -> service.refund(null));

        assertEquals(OrderException.Code.INVALID_REQUEST, exception.code());
    }
    @Test
    void directRefundCannotConsumeQuantityClaimedByOpenReturn() {
        for (OrderReturnStatus returnStatus : List.of(
                OrderReturnStatus.REQUESTED, OrderReturnStatus.RECEIVED, OrderReturnStatus.INSPECTION_FAILED)) {
            UUID sellerId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID orderItemId = UUID.randomUUID();
            PurchaseOrder order = mock(PurchaseOrder.class);
            when(order.sellerId()).thenReturn(sellerId);
            when(order.state()).thenReturn(OrderState.PAID);

            OrderItem item = mock(OrderItem.class);
            when(item.sellerId()).thenReturn(sellerId);
            when(item.orderItemId()).thenReturn(orderItemId);
            when(item.quantity()).thenReturn(3);

            OrderReturn openReturn = mock(OrderReturn.class);
            OrderReturnItem returnItem = mock(OrderReturnItem.class);
            when(openReturn.status()).thenReturn(returnStatus);
            when(openReturn.items()).thenReturn(List.of(returnItem));
            when(returnItem.orderItemId()).thenReturn(orderItemId);
            when(returnItem.quantity()).thenReturn(2);
            when(returnItem.inspectionResult()).thenReturn(returnStatus == OrderReturnStatus.INSPECTION_FAILED
                    ? OrderReturnInspectionResult.FAILED : null);

            OrderRepository orders = mock(OrderRepository.class);
            OrderItemRepository items = mock(OrderItemRepository.class);
            OrderFulfillmentRepository fulfillments = mock(OrderFulfillmentRepository.class);
            OrderRefundRepository refunds = mock(OrderRefundRepository.class);
            OrderReturnRepository returns = mock(OrderReturnRepository.class);
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
            OrderFulfillment fulfillment = mock(OrderFulfillment.class);
            when(fulfillment.status()).thenReturn(FulfillmentStatus.UNFULFILLED);
            when(fulfillments.findById(orderId)).thenReturn(Optional.of(fulfillment));
            when(items.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of(item));
            when(refunds.refundedQuantitiesByOrder(orderId)).thenReturn(Map.of());
            when(refunds.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(returns.findAllByOrderId(orderId)).thenReturn(List.of(openReturn));

        OrderRefundServiceImpl service = new OrderRefundServiceImpl(transactions, orders, items, fulfillments,
                refunds, returns, mock(InventoryService.class), mock(SettlementService.class),
                mock(PaymentService.class), Clock.systemUTC());
            OrderException exception = assertThrows(OrderException.class, () -> service.refund(
                    new OrderRefundRequest(sellerId, orderId, "direct-refund-" + returnStatus,
                            List.of(new OrderRefundRequest.Item(orderItemId, 2)))));

            assertEquals(OrderException.Code.CONFLICT, exception.code());
        }
    }
}
