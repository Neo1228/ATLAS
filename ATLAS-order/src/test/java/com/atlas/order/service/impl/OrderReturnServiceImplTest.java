package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderReturnResponse;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.OrderReturn;
import com.atlas.order.persistence.entity.OrderReturnItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderRefundPaymentStatus;
import com.atlas.order.persistence.state.OrderRefundStatus;
import com.atlas.order.persistence.state.OrderReturnStatus;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderReturnRepository;
import com.atlas.order.service.OrderRefundService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OrderReturnServiceImplTest {
    @Test
    void resumesSavedRefundAfterProcessingFailureOutsideTheInspectionTransaction() {
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        AtomicBoolean active = new AtomicBoolean();
        AtomicInteger processingAttempts = new AtomicInteger();

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
            assertFalse(active.get());
            active.set(true);
            return new SimpleTransactionStatus();
        });
        doAnswer(invocation -> {
            active.set(false);
            return null;
        }).when(transactionManager).commit(any());
        doAnswer(invocation -> {
            active.set(false);
            return null;
        }).when(transactionManager).rollback(any());

        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(mock(PurchaseOrder.class)));
        OrderReturnRepository returns = mock(OrderReturnRepository.class);
        OrderReturn row = new OrderReturn(orderId, sellerId, "buyer", "return-key", now);
        row.receive(now);
        row.addItem(new OrderReturnItem(orderItemId, 1));
        when(returns.findWithLockByOrderIdAndReturnId(orderId, returnId)).thenReturn(Optional.of(row));
        when(returns.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.orderItemId()).thenReturn(orderItemId);
        when(orderItem.refundAmountWon(1, 0)).thenReturn(100L);
        OrderItemRepository items = mock(OrderItemRepository.class);
        when(items.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of(orderItem));
        OrderRefundRepository refundRecords = mock(OrderRefundRepository.class);
        when(refundRecords.refundedQuantitiesByOrder(orderId)).thenReturn(Map.of());

        OrderRefundService refunds = mock(OrderRefundService.class);
        when(refunds.prepareInspectedReturnRefund(returnId)).thenAnswer(invocation -> {
            assertTrue(active.get(), "Refund facts must be reserved in the inspection transaction");
            if (processingAttempts.get() == 0) {
                assertNull(row.items().get(0).refundId());
                assertNull(row.items().get(0).refundAmountWon());
            } else {
                assertEquals(refundId, row.items().get(0).refundId());
                assertEquals(100L, row.items().get(0).refundAmountWon());
            }
            return new OrderRefundResponse(refundId, orderId, 100, OrderRefundStatus.FULLY_RECORDED,
                    OrderRefundPaymentStatus.PENDING, null, now);
        });
        when(refunds.recordInspectedReturnRefund(returnId)).thenAnswer(invocation -> {
            assertFalse(active.get(), "Refund processing must run outside the inspection transaction");
            if (processingAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("transient provider failure");
            }
            return new OrderRefundResponse(refundId, orderId, 100, OrderRefundStatus.FULLY_RECORDED,
                    OrderRefundPaymentStatus.COMPLETED, "provider-refund", now);
        });

        OrderReturnServiceImpl service = new OrderReturnServiceImpl(transactionManager, orders, items,
                mock(OrderFulfillmentRepository.class), refundRecords, returns, refunds,
                Clock.fixed(now, ZoneOffset.UTC));
        OrderReturnInspectionRequest request = new OrderReturnInspectionRequest(List.of(
                new OrderReturnInspectionRequest.Item(orderItemId, OrderReturnInspectionRequest.Result.PASSED)));

        assertThrows(IllegalStateException.class, () -> service.inspect(sellerId, orderId, returnId, request));
        assertFalse(active.get());
        assertEquals(OrderReturnStatus.RECEIVED, row.status());
        assertEquals(refundId, row.items().get(0).refundId());
        assertEquals(100L, row.items().get(0).refundAmountWon());

        OrderReturnResponse response = service.inspect(sellerId, orderId, returnId, request);

        assertEquals(OrderReturnStatus.REFUND_RECORDED, response.status());
        assertFalse(active.get());
        verify(refunds, times(2)).prepareInspectedReturnRefund(returnId);
        verify(refunds, times(2)).recordInspectedReturnRefund(returnId);
    }
}
