package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.service.AdmissionService;
import com.atlas.cart.dto.response.CartItemResponse;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.JdbcBuyerLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.service.PaymentService;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CheckoutBehaviorTest {
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID CAMPAIGN = UUID.randomUUID();
    private static final UUID TICKET = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void sameScopedCheckoutKeyReplaysExistingOrderWithoutRelockingCart() throws Exception {
        CartService carts = mock(CartService.class);
        OrderRepository repository = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        UUID orderId = UUID.randomUUID();
        PurchaseOrder prior = cartOrder(SELLER, "buyer", "same-key", orderId);
        OrderResponse expected = mock(OrderResponse.class);
        when(repository.findBySellerIdAndBuyerRefAndCheckoutKey(SELLER, "buyer", "same-key"))
                .thenReturn(Optional.of(prior));
        when(orderService.find(orderId)).thenReturn(expected);

        CartCheckoutServiceImpl service = new CartCheckoutServiceImpl(carts, mock(SellerSkuService.class),
                mock(InventoryService.class), repository, mock(OrderItemRepository.class), orderService,
                transactionManager(), fixedClock(), Duration.ofMinutes(10));

        assertEquals(expected, service.checkout(SELLER, "buyer", "same-key"));
        verify(carts, never()).lockForCheckout(any(), any());
    }


    @Test
    void campaignSoldOutMarksOrderFailedAndReleasesAdmissionSlot() throws Exception {
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        AdmissionService admission = mock(AdmissionService.class);
        InventoryService inventory = mock(InventoryService.class);
        SellerSkuService catalog = mock(SellerSkuService.class);
        UUID productId = UUID.randomUUID();
        QueueTicketResponse ticket = new QueueTicketResponse(TICKET, CAMPAIGN, SELLER, "SKU-1", "buyer", 1,
                TicketStatus.CHECKOUT_STARTED, NOW.plusSeconds(600), true);
        when(orders.findByTicketOrNull(TICKET)).thenReturn(null);
        when(admission.requireCheckoutSlot(CAMPAIGN, TICKET)).thenReturn(ticket);
        when(admission.startCheckout(CAMPAIGN, TICKET)).thenReturn(ticket);
        when(catalog.find(SELLER, "SKU-1"))
                .thenReturn(new SellerSkuResponse(SELLER, productId, "SKU-1", "Product", 1_500));
        when(inventory.tryReserve(any())).thenReturn(Optional.empty());
        when(inventory.findReservations(any())).thenReturn(List.of());
        when(admission.find(CAMPAIGN, TICKET)).thenReturn(ticket);
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder order = invocation.getArgument(0);
            setOrderId(order, UUID.randomUUID());
            when(orders.requireById(order.orderId())).thenReturn(order);
            return order;
        });

        OrderServiceImpl service = new OrderServiceImpl(transactionManager(), orders, items,
                mock(JdbcBuyerLockRepository.class), mock(OrderFulfillmentService.class), admission,
                inventory, mock(PaymentService.class), catalog, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10));
        service.startCheckout(CAMPAIGN, TICKET);

        verify(orders).updateState(any(UUID.class), eq(OrderState.FAILED), eq(null), any(Instant.class));
        verify(admission).releaseCheckoutSlot(CAMPAIGN, TICKET,
                CheckoutSlotReleaseReason.INVENTORY_UNAVAILABLE);
    }

    @Test
    void checkoutRevalidatesAllSkusWithOneCatalogBatchLookup() throws Exception {
        CartService carts = mock(CartService.class);
        SellerSkuService catalog = mock(SellerSkuService.class);
        InventoryService inventory = mock(InventoryService.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        OrderService orderService = mock(OrderService.class);
        UUID productOne = UUID.randomUUID();
        UUID productTwo = UUID.randomUUID();
        List<CartItemResponse> cartItems = List.of(
                new CartItemResponse(productOne, "SKU-1", 2, "Old name", 10),
                new CartItemResponse(productTwo, "SKU-2", 1, "Old name", 10));
        CartResponse cart = new CartResponse(UUID.randomUUID(), SELLER, "buyer", cartItems);
        when(carts.lockForCheckout(SELLER, "buyer")).thenReturn(cart);
        when(orders.findBySellerIdAndBuyerRefAndCheckoutKey(SELLER, "buyer", "checkout-key"))
                .thenReturn(Optional.empty());
        when(catalog.findAll(SELLER, List.of("SKU-1", "SKU-2"))).thenReturn(List.of(
                new SellerSkuResponse(SELLER, productOne, "SKU-1", "Current one", 100),
                new SellerSkuResponse(SELLER, productTwo, "SKU-2", "Current two", 200)));
        UUID orderId = UUID.randomUUID();
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder order = invocation.getArgument(0);
            setOrderId(order, orderId);
            return order;
        });
        OrderResponse expected = mock(OrderResponse.class);
        when(orderService.find(orderId)).thenReturn(expected);
        CartCheckoutServiceImpl service = new CartCheckoutServiceImpl(carts, catalog, inventory, orders, items,
                orderService, transactionManager(), fixedClock(), Duration.ofMinutes(10));

        assertEquals(expected, service.checkout(SELLER, "buyer", "checkout-key"));

        verify(catalog).findAll(SELLER, List.of("SKU-1", "SKU-2"));
        verify(inventory).reserveAll(any(ReserveInventoryBatchRequest.class));
        verify(carts).clearForCheckout(SELLER, "buyer");
    }

    @Test
    void checkoutRejectsStaleAndMissingCatalogSkus() throws Exception {
        assertRejectedCatalogResult(List.of(
                new SellerSkuResponse(SELLER, UUID.randomUUID(), "SKU-1", "Different product", 100)));
        assertRejectedCatalogResult(List.of());
    }

    private static void assertRejectedCatalogResult(List<SellerSkuResponse> catalogRows) throws Exception {
        CartService carts = mock(CartService.class);
        SellerSkuService catalog = mock(SellerSkuService.class);
        InventoryService inventory = mock(InventoryService.class);
        OrderRepository orders = mock(OrderRepository.class);
        UUID productId = UUID.randomUUID();
        CartResponse cart = new CartResponse(UUID.randomUUID(), SELLER, "buyer",
                List.of(new CartItemResponse(productId, "SKU-1", 1, "Product", 100)));
        when(carts.lockForCheckout(SELLER, "buyer")).thenReturn(cart);
        when(orders.findBySellerIdAndBuyerRefAndCheckoutKey(SELLER, "buyer", "checkout-key"))
                .thenReturn(Optional.empty());
        when(catalog.findAll(SELLER, List.of("SKU-1"))).thenReturn(catalogRows);
        CartCheckoutServiceImpl service = new CartCheckoutServiceImpl(carts, catalog, inventory, orders,
                mock(OrderItemRepository.class), mock(OrderService.class), transactionManager(),
                fixedClock(), Duration.ofMinutes(10));

        OrderException conflict = assertThrows(OrderException.class,
                () -> service.checkout(SELLER, "buyer", "checkout-key"));

        assertEquals(OrderException.Code.CONFLICT, conflict.code());
        verify(catalog).findAll(SELLER, List.of("SKU-1"));
        verify(orders, never()).save(any(PurchaseOrder.class));
        verify(inventory, never()).reserveAll(any(ReserveInventoryBatchRequest.class));
        verify(carts, never()).clearForCheckout(SELLER, "buyer");
    }

    private static CartCheckoutServiceImpl cartCheckout(CartService carts, SellerSkuService catalog,
            InventoryService inventory, OrderRepository orders, OrderItemRepository items,
            OrderService orderService, List<CartItemResponse> cartItems, List<SellerSkuResponse> catalogRows)
            throws Exception {
        when(carts.lockForCheckout(SELLER, "buyer")).thenReturn(
                new CartResponse(UUID.randomUUID(), SELLER, "buyer", cartItems));
        when(orders.findBySellerIdAndBuyerRefAndCheckoutKey(SELLER, "buyer", "checkout-key"))
                .thenReturn(Optional.empty());
        when(catalog.findAll(SELLER, cartItems.stream().map(CartItemResponse::skuId).distinct().sorted().toList()))
                .thenReturn(catalogRows);
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder order = invocation.getArgument(0);
            setOrderId(order, UUID.randomUUID());
            return order;
        });
        return new CartCheckoutServiceImpl(carts, catalog, inventory, orders, items, orderService,
                transactionManager(), fixedClock(), Duration.ofMinutes(10));
    }

    private static PurchaseOrder cartOrder(UUID seller, String buyer, String key, UUID id) throws Exception {
        PurchaseOrder order = new PurchaseOrder(seller, buyer, key, OrderState.RESERVED,
                NOW.plusSeconds(600), NOW, NOW);
        setOrderId(order, id);
        return order;
    }

    private static void setOrderId(PurchaseOrder order, UUID id) throws Exception {
        Field field = PurchaseOrder.class.getDeclaredField("orderId");
        field.setAccessible(true);
        field.set(order, id);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) { }

            @Override
            public void rollback(TransactionStatus status) { }
        };
    }
}
