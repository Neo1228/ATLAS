package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.cart.dto.request.ReorderCartRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.exception.OrderException;
import com.atlas.order.dto.request.OrderReorderRequest;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.projection.ExistingReorderRequest;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.JdbcOrderReorderRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderReorderServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private final UUID orderId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final String buyerRef = "local-buyer";
    private final String skuId = "sku-1";

    @Mock private OrderRepository orders;
    @Mock private OrderItemRepository items;
    @Mock private InventoryService inventory;
    @Mock private CartService carts;
    @Mock private JdbcOrderReorderRepository reorderRequests;
    @Mock private Clock clock;

    @InjectMocks private OrderReorderServiceImpl service;

    @Test
    void terminalCartOrderMergesCurrentCartAndRecordsIdempotencyKey() {
        PurchaseOrder order = terminalCartOrder(OrderState.CANCELLED);
        OrderItem item = new OrderItem(order, productId, sellerId, skuId, "Original name", 2, 100);
        CartResponse expected = new CartResponse(UUID.randomUUID(), sellerId, buyerRef, List.of());
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
        when(reorderRequests.find(sellerId, buyerRef, "reorder-key")).thenReturn(Optional.empty());
        when(items.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of(item));
        when(clock.instant()).thenReturn(NOW);
        when(reorderRequests.insertIfAbsent(sellerId, buyerRef, "reorder-key", orderId, true, NOW))
                .thenReturn(true);
        when(inventory.findAll(sellerId, List.of(skuId)))
                .thenReturn(List.of(new InventoryPoolResponse(sellerId, skuId, 5, 3, 2, 0)));
        when(carts.mergeItems(any(ReorderCartRequest.class))).thenReturn(expected);

        assertEquals(expected, service.reorder(new OrderReorderRequest(orderId, "reorder-key", true)));
        verify(carts).mergeItems(any(ReorderCartRequest.class));
        assertEquals(OrderState.CANCELLED, order.state());
    }

    @Test
    void duplicateKeyForSameOrderReturnsCurrentCartWithoutMergingAgain() {
        PurchaseOrder order = terminalCartOrder(OrderState.PAID);
        CartResponse expected = new CartResponse(UUID.randomUUID(), sellerId, buyerRef, List.of());
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
        when(reorderRequests.find(sellerId, buyerRef, "reorder-key"))
                .thenReturn(Optional.of(new ExistingReorderRequest(orderId, true)));
        when(carts.find(sellerId, buyerRef)).thenReturn(expected);

        assertEquals(expected, service.reorder(new OrderReorderRequest(orderId, "reorder-key", true)));
        verify(carts).find(sellerId, buyerRef);
        verify(carts, never()).mergeItems(any(ReorderCartRequest.class));
        verify(inventory, never()).findAll(any(), any());
    }

    @Test
    void rejectsCampaignAndNonterminalOrdersBeforeReadingItemsOrCart() {
        Instant expiry = NOW.plusSeconds(600);
        PurchaseOrder campaignOrder = new PurchaseOrder(UUID.randomUUID(), sellerId, UUID.randomUUID(), buyerRef,
                OrderState.PAID, expiry, null, NOW, NOW);
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(campaignOrder));

        OrderException campaign = assertThrows(OrderException.class,
                () -> service.reorder(new OrderReorderRequest(orderId, "reorder-key", true)));
        assertEquals(OrderException.Code.CONFLICT, campaign.code());

        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(terminalCartOrder(OrderState.RESERVED)));
        OrderException active = assertThrows(OrderException.class,
                () -> service.reorder(new OrderReorderRequest(orderId, "reorder-key", true)));
        assertEquals(OrderException.Code.CONFLICT, active.code());
        verify(items, never()).findByOrderOrderIdOrderBySkuIdAsc(orderId);
        verify(carts, never()).mergeItems(any(ReorderCartRequest.class));
    }

    @Test
    void unavailableInventoryRejectsWholeReorderBeforeCartMutation() {
        PurchaseOrder order = terminalCartOrder(OrderState.EXPIRED);
        OrderItem item = new OrderItem(order, productId, sellerId, skuId, "Original name", 2, 100);
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
        when(reorderRequests.find(sellerId, buyerRef, "reorder-key")).thenReturn(Optional.empty());
        when(items.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of(item));
        when(inventory.findAll(sellerId, List.of(skuId)))
                .thenReturn(List.of(new InventoryPoolResponse(sellerId, skuId, 1, 1, 0, 0)));

        InventoryException unavailable = assertThrows(InventoryException.class,
                () -> service.reorder(new OrderReorderRequest(orderId, "reorder-key", true)));
        assertEquals(InventoryException.Code.INSUFFICIENT_STOCK, unavailable.code());
        verify(reorderRequests, never()).insertIfAbsent(eq(sellerId), eq(buyerRef), eq("reorder-key"),
                eq(orderId), eq(true), any(Instant.class));
        verify(carts, never()).mergeItems(any(ReorderCartRequest.class));
    }

    @Test
    void rejectsNullUseCaseRequestAsInvalidRequest() {
        OrderException exception = assertThrows(OrderException.class, () -> service.reorder(null));
        assertEquals(OrderException.Code.INVALID_REQUEST, exception.code());
    }

    private PurchaseOrder terminalCartOrder(OrderState state) {
        return new PurchaseOrder(sellerId, buyerRef, "checkout-key", state, NOW.plusSeconds(600), NOW, NOW);
    }
}
