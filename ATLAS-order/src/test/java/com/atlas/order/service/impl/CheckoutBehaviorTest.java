package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.service.AdmissionService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.persistence.state.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.catalog.service.SellerCouponService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.BuyerCheckoutRepository;
import com.atlas.order.repository.JdbcBuyerLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.service.SettlementService;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    void campaignCheckoutReleasesSlotWhenInventoryCannotBeReserved() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        AdmissionService admission = mock(AdmissionService.class);
        InventoryService inventory = mock(InventoryService.class);
        SellerSkuService catalog = mock(SellerSkuService.class);
        UUID productId = UUID.randomUUID();
        QueueTicketResponse ticket = new QueueTicketResponse(TICKET, CAMPAIGN, SELLER, "SKU-1", "buyer", 1,
                TicketStatus.CHECKOUT_STARTED, NOW.plusSeconds(600), true);
        when(orders.findByTicketId(TICKET)).thenReturn(Optional.empty());
        when(admission.requireCheckoutSlot(CAMPAIGN, TICKET)).thenReturn(ticket);
        when(admission.startCheckout(CAMPAIGN, TICKET)).thenReturn(ticket);
        when(catalog.findOrderableSellerSku(eq(SELLER), eq("SKU-1"), eq(ProductSalesChannel.CAMPAIGN),
                any(Instant.class)))
                .thenReturn(new SellerSkuResponse(SELLER, productId, "SKU-1", "Product", 1_500,
                        1_200L, NOW.minusSeconds(1), NOW.plusSeconds(600), 1_200));
        when(inventory.tryReserve(any())).thenReturn(Optional.empty());
        when(inventory.findReservations(any())).thenReturn(List.of());
        when(admission.find(CAMPAIGN, TICKET)).thenReturn(ticket);
        AtomicReference<PurchaseOrder> savedOrder = new AtomicReference<>();
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder order = invocation.getArgument(0);
            setOrderId(order, UUID.randomUUID());
            savedOrder.set(order);
            return order;
        });
        when(orders.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedOrder.get()));

        OrderServiceImpl service = new OrderServiceImpl(transactionManager(), orders, items,
                mock(JdbcBuyerLockRepository.class), mock(OrderFulfillmentService.class), admission,
                inventory, mock(PaymentService.class), catalog, mock(SettlementService.class),
                fixedClock(), Duration.ofMinutes(10));

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        service.startCheckout(CAMPAIGN, TICKET);
        verify(items).save(itemCaptor.capture());
        assertEquals(1_200, itemCaptor.getValue().unitPriceWon());

        verify(admission).releaseCheckoutSlot(CAMPAIGN, TICKET,
                CheckoutSlotReleaseReason.INVENTORY_UNAVAILABLE);
    }

    @Test
    void unavailableCampaignProductReleasesSlotAndCommitsBeforeThrowing() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        AdmissionService admission = mock(AdmissionService.class);
        InventoryService inventory = mock(InventoryService.class);
        SellerSkuService catalog = mock(SellerSkuService.class);
        QueueTicketResponse ticket = new QueueTicketResponse(TICKET, CAMPAIGN, SELLER, "SKU-1", "buyer", 1,
                TicketStatus.CHECKOUT_STARTED, NOW.plusSeconds(600), true);
        when(orders.findByTicketId(TICKET)).thenReturn(Optional.empty());
        when(admission.requireCheckoutSlot(CAMPAIGN, TICKET)).thenReturn(ticket);
        when(catalog.findOrderableSellerSku(eq(SELLER), eq("SKU-1"), eq(ProductSalesChannel.CAMPAIGN),
                any(Instant.class)))
                .thenThrow(new CatalogException(CatalogException.Code.PRODUCT_NOT_ORDERABLE, "unavailable"));
        AtomicReference<Boolean> committed = new AtomicReference<>(false);
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { committed.set(true); }
            @Override public void rollback(TransactionStatus status) { }
        };
        OrderServiceImpl service = new OrderServiceImpl(manager, orders, items,
                mock(JdbcBuyerLockRepository.class), mock(OrderFulfillmentService.class), admission,
                inventory, mock(PaymentService.class), catalog, mock(SettlementService.class),
                fixedClock(), Duration.ofMinutes(10));

        CatalogException exception = assertThrows(CatalogException.class,
                () -> service.startCheckout(CAMPAIGN, TICKET));

        assertEquals(CatalogException.Code.PRODUCT_NOT_ORDERABLE, exception.code());
        assertEquals(true, committed.get());
        verify(admission).releaseCheckoutSlot(CAMPAIGN, TICKET,
                CheckoutSlotReleaseReason.PRODUCT_NOT_ORDERABLE);
        verify(admission, never()).startCheckout(CAMPAIGN, TICKET);
        verify(orders, never()).save(any(PurchaseOrder.class));
        verify(items, never()).save(any(OrderItem.class));
        verify(inventory, never()).tryReserve(any());
    }

    @Test
    void reconcilesCapturedOrderPaymentAndRecordsSettlement() {
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        PurchaseOrder order = new PurchaseOrder(SELLER, "buyer", "checkout-key",
                OrderState.PAYMENT_SUBMITTED, NOW.plusSeconds(600), NOW, NOW);
        setOrderId(order, orderId);
        OrderItem orderItem = new OrderItem(order, UUID.randomUUID(), SELLER, "SKU-1", "Product", 2, 1_000);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        OrderFulfillmentService fulfillment = mock(OrderFulfillmentService.class);
        PaymentService payments = mock(PaymentService.class);
        SettlementService settlement = mock(SettlementService.class);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
        when(items.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of(orderItem));
        when(inventory.findReservations(orderId)).thenReturn(List.of());
        when(payments.find(orderId)).thenReturn(
                new PaymentResponse(orderId, attemptId, PaymentState.CAPTURED, false));

        OrderServiceImpl service = new OrderServiceImpl(transactionManager(), orders, items,
                mock(JdbcBuyerLockRepository.class), fulfillment, mock(AdmissionService.class), inventory,
                payments, mock(SellerSkuService.class), settlement, fixedClock(), Duration.ofMinutes(10));

        OrderResponse response = service.reconcilePayment(orderId, "captured-reconciliation");

        assertEquals(OrderState.PAID, response.state());
        assertEquals(PaymentState.CAPTURED, response.paymentState());
        verify(settlement).recordCapture(new CapturePostingRequest(orderId, attemptId, SELLER, 2_000));
        verify(inventory).claimAll(orderId);
        verify(inventory, never()).releaseAll(orderId);
        verify(fulfillment).initialize(orderId);
    }

    @Test
    void capturedPaymentWinsCancellationRaceAndIsRecorded() {
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        BuyerCheckout checkout = new BuyerCheckout("buyer", "checkout-key", BuyerCheckoutState.PAYMENT_PENDING,
                1_500, NOW, NOW);
        setCheckoutId(checkout, checkoutId);
        PurchaseOrder order = new PurchaseOrder(SELLER, "buyer", "checkout-key", OrderState.PAYMENT_SUBMITTED,
                NOW.plusSeconds(600), NOW, NOW);
        setOrderId(order, UUID.randomUUID());
        order.setCheckoutId(checkoutId);
        OrderItem item = new OrderItem(order, UUID.randomUUID(), SELLER, "SKU-1", "Product", 1, 1_500);

        BuyerCheckoutRepository checkouts = mock(BuyerCheckoutRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        OrderFulfillmentService fulfillment = mock(OrderFulfillmentService.class);
        PaymentService payments = mock(PaymentService.class);
        SettlementService settlement = mock(SettlementService.class);
        when(checkouts.findWithLockByCheckoutId(checkoutId)).thenReturn(Optional.of(checkout));
        when(checkouts.findById(checkoutId)).thenReturn(Optional.of(checkout));
        when(orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId)).thenReturn(List.of(order));
        when(items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId())).thenReturn(List.of(item));
        when(payments.findCheckout(checkoutId))
                .thenReturn(new PaymentResponse(null, checkoutId, null, PaymentState.NOT_STARTED, false));

        CheckoutPaymentServiceImpl service = new CheckoutPaymentServiceImpl(transactionManager(), checkouts,
                orders, items, inventory, fulfillment, payments, settlement, mock(SellerCouponService.class),
                fixedClock());
        AtomicReference<BuyerCheckoutState> cancellationState = new AtomicReference<>();
        when(payments.submitCheckout(eq(checkoutId), any())).thenAnswer(invocation -> {
            cancellationState.set(service.cancel(checkoutId, "cancel-racing-submit").state());
            return new PaymentResponse(null, checkoutId, attemptId, PaymentState.CAPTURED, false);
        });

        service.submit(checkoutId);

        assertEquals(BuyerCheckoutState.CANCEL_PENDING, cancellationState.get());
        assertEquals(BuyerCheckoutState.PAID, checkout.state());
        assertEquals(OrderState.PAID, order.state());
        verify(inventory).claimAll(order.orderId());
        verify(inventory, never()).releaseAll(order.orderId());
        verify(fulfillment).initialize(order.orderId());
        verify(settlement).recordCheckoutCapture(any());
    }

    private static void setOrderId(PurchaseOrder order, UUID id) {
        try {
            var field = PurchaseOrder.class.getDeclaredField("orderId");
            field.setAccessible(true);
            field.set(order, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setCheckoutId(BuyerCheckout checkout, UUID id) {
        try {
            var field = BuyerCheckout.class.getDeclaredField("checkoutId");
            field.setAccessible(true);
            field.set(checkout, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
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
