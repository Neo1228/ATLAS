package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.persistence.projection.RefundItem;
import com.atlas.order.persistence.projection.RefundRecord;
import com.atlas.order.persistence.state.OrderRefundStatus;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(refunds.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(new RefundRecord(
                refundId, orderId, sellerId, idempotencyKey, 200, OrderRefundStatus.PARTIALLY_RECORDED,
                Instant.parse("2026-01-01T00:00:00Z"), List.of(new RefundItem(javaSecond, 1),
                new RefundItem(javaFirst, 1)))));
        OrderRefundServiceImpl service = new OrderRefundServiceImpl(transactions, mock(OrderRepository.class),
                mock(OrderItemRepository.class), mock(OrderFulfillmentRepository.class), refunds,
                mock(InventoryService.class), mock(SettlementService.class),
                Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));

        OrderRefundResponse response = service.refund(sellerId, orderId, idempotencyKey,
                new OrderRefundRequest(List.of(new OrderRefundRequest.Item(javaSecond, 1),
                        new OrderRefundRequest.Item(javaFirst, 1))));

        assertEquals(refundId, response.refundId());
    }
}
