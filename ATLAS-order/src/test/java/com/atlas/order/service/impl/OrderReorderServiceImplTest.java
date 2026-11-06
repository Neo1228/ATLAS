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
import com.atlas.order.dto.request.OrderReorderRequest;
import com.atlas.order.exception.OrderException;
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
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String BUYER_REF = "local-buyer";
    private static final String SKU_ID = "sku-1";

    @Mock OrderRepository orders;
    @Mock OrderItemRepository items;
    @Mock InventoryService inventory;
    @Mock CartService carts;
    @Mock JdbcOrderReorderRepository reorderRequests;
    @Mock Clock clock;
    @InjectMocks OrderReorderServiceImpl service;

    @Test
    void reorderMergesSourceItemsIntoBuyerWideCartWithSourceSeller() {
        PurchaseOrder order = terminalCartOrder(OrderState.CANCELLED);
        OrderItem item = new OrderItem(order, PRODUCT_ID, SELLER_ID, SKU_ID, "Product", 2, 100);
        CartResponse expected = new CartResponse(UUID.randomUUID(), "cart", List.of());
        when(orders.findWithLockByOrderId(ORDER_ID)).thenReturn(Optional.of(order));
        when(reorderRequests.find(SELLER_ID, BUYER_REF, "reorder-key")).thenReturn(Optional.empty());
        when(items.findByOrderOrderIdOrderBySkuIdAsc(ORDER_ID)).thenReturn(List.of(item));
        when(clock.instant()).thenReturn(NOW);
        when(reorderRequests.insertIfAbsent(SELLER_ID, BUYER_REF, "reorder-key", ORDER_ID, true, NOW))
                .thenReturn(true);
        when(inventory.findAll(SELLER_ID, List.of(SKU_ID)))
                .thenReturn(List.of(new InventoryPoolResponse(SELLER_ID, SKU_ID, 5, 3, 2, 0)));
        when(carts.mergeItems(any(ReorderCartRequest.class))).thenReturn(expected);

        assertEquals(expected, service.reorder(new OrderReorderRequest(ORDER_ID, "reorder-key", true)));

        verify(carts).mergeItems(new ReorderCartRequest(BUYER_REF, true,
                List.of(new ReorderCartRequest.Item(SELLER_ID, PRODUCT_ID, SKU_ID, 2))));
        verify(carts, never()).find(BUYER_REF);
    }

    @Test
    void replayFindsCartByBuyerAndDoesNotMergeAgain() {
        PurchaseOrder order = terminalCartOrder(OrderState.PAID);
        CartResponse expected = new CartResponse(UUID.randomUUID(), "cart", List.of());
        when(orders.findWithLockByOrderId(ORDER_ID)).thenReturn(Optional.of(order));
        when(reorderRequests.find(SELLER_ID, BUYER_REF, "reorder-key"))
                .thenReturn(Optional.of(new ExistingReorderRequest(ORDER_ID, true)));
        when(carts.find(BUYER_REF)).thenReturn(expected);

        assertEquals(expected, service.reorder(new OrderReorderRequest(ORDER_ID, "reorder-key", true)));
        verify(carts).find(BUYER_REF);
        verify(carts, never()).mergeItems(any(ReorderCartRequest.class));
        verify(inventory, never()).findAll(any(), any());
    }

    @Test
    void rejectsCampaignAndActiveOrders() {
        PurchaseOrder campaignOrder = new PurchaseOrder(CAMPAIGN_ID, SELLER_ID, UUID.randomUUID(),
                BUYER_REF, OrderState.PAID, NOW.plusSeconds(600), null, NOW, NOW);
        when(orders.findWithLockByOrderId(ORDER_ID)).thenReturn(Optional.of(campaignOrder));
        OrderException campaign = assertThrows(OrderException.class,
                () -> service.reorder(new OrderReorderRequest(ORDER_ID, "reorder-key", false)));
        assertEquals(OrderException.Code.CONFLICT, campaign.code());

        when(orders.findWithLockByOrderId(ORDER_ID)).thenReturn(Optional.of(terminalCartOrder(OrderState.RESERVED)));
        OrderException active = assertThrows(OrderException.class,
                () -> service.reorder(new OrderReorderRequest(ORDER_ID, "reorder-key", false)));
        assertEquals(OrderException.Code.CONFLICT, active.code());
        verify(items, never()).findByOrderOrderIdOrderBySkuIdAsc(ORDER_ID);
        verify(carts, never()).mergeItems(any(ReorderCartRequest.class));
    }

    @Test
    void rejectsInsufficientInventoryBeforeRecordingOrMerging() {
        PurchaseOrder order = terminalCartOrder(OrderState.EXPIRED);
        OrderItem item = new OrderItem(order, PRODUCT_ID, SELLER_ID, SKU_ID, "Product", 2, 100);
        when(orders.findWithLockByOrderId(ORDER_ID)).thenReturn(Optional.of(order));
        when(reorderRequests.find(SELLER_ID, BUYER_REF, "reorder-key")).thenReturn(Optional.empty());
        when(items.findByOrderOrderIdOrderBySkuIdAsc(ORDER_ID)).thenReturn(List.of(item));
        when(inventory.findAll(SELLER_ID, List.of(SKU_ID)))
                .thenReturn(List.of(new InventoryPoolResponse(SELLER_ID, "SKU-1", 1, 1, 0, 0)));

        InventoryException unavailable = assertThrows(InventoryException.class,
                () -> service.reorder(new OrderReorderRequest(ORDER_ID, "reorder-key", false)));
        assertEquals(InventoryException.Code.INSUFFICIENT_STOCK, unavailable.code());
        verify(reorderRequests, never()).insertIfAbsent(eq(SELLER_ID), eq(BUYER_REF), eq("reorder-key"),
                eq(ORDER_ID), eq(false), any(Instant.class));
        verify(carts, never()).mergeItems(any(ReorderCartRequest.class));
    }

    @Test
    void rejectsNullRequest() {
        OrderException exception = assertThrows(OrderException.class, () -> service.reorder(null));
        assertEquals(OrderException.Code.INVALID_REQUEST, exception.code());
    }

    private static PurchaseOrder terminalCartOrder(OrderState state) {
        return new PurchaseOrder(SELLER_ID, BUYER_REF, "checkout-key", state,
                NOW.plusSeconds(600), NOW, NOW);
    }
}
