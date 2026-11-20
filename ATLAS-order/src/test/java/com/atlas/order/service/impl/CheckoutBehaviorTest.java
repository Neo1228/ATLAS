package com.atlas.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.atlas.cart.service.CartService;
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
import com.atlas.order.repository.JdbcExpirySweepLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CheckoutIdempotencyKeys;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
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
    void reconciliationOfCancellationDoesNotStartCapture() {
        UUID checkoutId = UUID.randomUUID();
        BuyerCheckout checkout = new BuyerCheckout("buyer", "cancel-key", BuyerCheckoutState.CANCEL_PENDING,
                100, NOW, NOW);
        PurchaseOrder order = new PurchaseOrder(SELLER, "buyer", "cancel-key", OrderState.CANCEL_PENDING,
                NOW.plusSeconds(600), NOW, NOW);
        order.setCheckoutId(checkoutId);
        BuyerCheckoutRepository checkouts = mock(BuyerCheckoutRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        PaymentService payments = mock(PaymentService.class);
        InventoryService inventory = mock(InventoryService.class);
        PaymentResponse payment = mock(PaymentResponse.class);
        when(payment.checkoutId()).thenReturn(checkoutId);
        when(payment.state()).thenReturn(PaymentState.UNKNOWN);
        when(checkouts.findById(checkoutId)).thenReturn(Optional.of(checkout));
        when(checkouts.findWithLockByCheckoutId(checkoutId)).thenReturn(Optional.of(checkout));
        when(orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId)).thenReturn(List.of(order));
        when(payments.findCheckout(checkoutId)).thenReturn(payment);
        when(payments.reconcileCheckoutForCancellation(checkoutId, "reconcile")).thenReturn(payment);
        CheckoutPaymentServiceImpl service = new CheckoutPaymentServiceImpl(transactionManager(),
                checkouts, orders, mock(OrderItemRepository.class), inventory,
                mock(OrderFulfillmentService.class), payments, mock(SettlementService.class),
                mock(SellerCouponService.class), fixedClock(), 100, Duration.ofSeconds(2), sweepLock());

        service.reconcile(checkoutId, "reconcile");

        verify(payments).reconcileCheckoutForCancellation(checkoutId, "reconcile");
        verify(payments, never()).reconcileCheckout(checkoutId, "reconcile");
        verify(inventory, never()).releaseAll(order.orderId());
    }

    @Test
    void checkoutCreationRejectsKeysThatReconcileAndCancelWouldRefuse() {
        // 생성은 256자, reconcile·cancel 은 221자를 받던 비대칭. 222자 키로 checkout 이
        // 만들어지면, 이후 결제 상태 확인과 취소가 키 길이 때문에 막혀 복구 경로가 없다.
        CartCheckoutServiceImpl service = new CartCheckoutServiceImpl(
                mock(CartService.class), mock(SellerSkuService.class), mock(SellerCouponService.class),
                mock(InventoryService.class), mock(BuyerCheckoutRepository.class), mock(OrderRepository.class),
                mock(OrderItemRepository.class), mock(OrderService.class), transactionManager(),
                fixedClock(), Duration.ofMinutes(10));
        String keyAcceptedByCreateButNotByReconcile = "k".repeat(222);

        OrderException exception = assertThrows(OrderException.class,
                () -> service.checkout("buyer-split-limit", keyAcceptedByCreateButNotByReconcile));

        assertEquals(OrderException.Code.INVALID_REQUEST, exception.code());
    }

    @Test
    void checkoutCreationAcceptsTheSharedMaximumKey() {
        // 221자는 path 전 구간(생성·reconcile·cancel)이 함께 받는 경계값이다.
        assertFalse(CheckoutIdempotencyKeys.isTooLong("k".repeat(CheckoutIdempotencyKeys.MAX_LENGTH)));
        assertTrue(CheckoutIdempotencyKeys.isTooLong("k".repeat(CheckoutIdempotencyKeys.MAX_LENGTH + 1)));
    }

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
                fixedClock(), Duration.ofMinutes(10), 100, Duration.ofSeconds(2),
                sweepLock());

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
                fixedClock(), Duration.ofMinutes(10), 100, Duration.ofSeconds(2),
                sweepLock());

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
                payments, mock(SellerSkuService.class), settlement, fixedClock(), Duration.ofMinutes(10), 100, Duration.ofSeconds(2),
                sweepLock());

        OrderResponse response = service.reconcilePayment(orderId, "captured-reconciliation");

        assertEquals(OrderState.PAID, response.state());
        assertEquals(PaymentState.CAPTURED, response.paymentState());
        verify(settlement).recordCapture(new CapturePostingRequest(orderId, attemptId, SELLER, 2_000));
        verify(inventory).claimAll(orderId);
        verify(inventory, never()).releaseAll(orderId);
        verify(fulfillment).initialize(orderId);
    }


    @Test
    void reconciliationResumesPaymentAttemptPersistedBeforePhaseReservation() {
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        PurchaseOrder order = new PurchaseOrder(SELLER, "buyer", "checkout-key",
                OrderState.PAYMENT_SUBMITTED, NOW.plusSeconds(600), NOW, NOW);
        setOrderId(order, orderId);
        OrderItem orderItem = new OrderItem(order, UUID.randomUUID(), SELLER, "SKU-1", "Product", 2, 1_000);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        PaymentService payments = mock(PaymentService.class);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(orders.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
        when(items.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of(orderItem));
        when(inventory.findReservations(orderId)).thenReturn(List.of());
        when(payments.find(orderId)).thenReturn(
                new PaymentResponse(orderId, attemptId, PaymentState.NOT_STARTED, false),
                new PaymentResponse(orderId, attemptId, PaymentState.UNKNOWN, true));
        when(payments.reconcile(orderId, "crash-recovery-key")).thenReturn(
                new PaymentResponse(orderId, attemptId, PaymentState.UNKNOWN, true));

        OrderServiceImpl service = new OrderServiceImpl(transactionManager(), orders, items,
                mock(JdbcBuyerLockRepository.class), mock(OrderFulfillmentService.class),
                mock(AdmissionService.class), inventory, payments, mock(SellerSkuService.class),
                mock(SettlementService.class), fixedClock(), Duration.ofMinutes(10), 100, Duration.ofSeconds(2),
                sweepLock());

        OrderResponse response = service.reconcilePayment(orderId, "crash-recovery-key");

        assertEquals(OrderState.PAYMENT_UNKNOWN, response.state());
        assertEquals(PaymentState.UNKNOWN, response.paymentState());
        verify(payments).reconcile(orderId, "crash-recovery-key");
        verify(inventory, never()).claimAll(orderId);
        verify(inventory, never()).releaseAll(orderId);
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
        when(payments.prepareCheckout(eq(checkoutId), any()))
                .thenReturn(new PaymentResponse(null, checkoutId, attemptId, PaymentState.NOT_STARTED, false));

        CheckoutPaymentServiceImpl service = new CheckoutPaymentServiceImpl(transactionManager(), checkouts,
                orders, items, inventory, fulfillment, payments, settlement, mock(SellerCouponService.class),
                fixedClock(), 100, Duration.ofSeconds(2),
                sweepLock());
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


    @Test
    void expirySweepIsBoundedByTheConfiguredBatchSize() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        when(orders.findReservedOrdersExpiredBy(any(Instant.class), any())).thenReturn(List.of());

        OrderServiceImpl service = new OrderServiceImpl(transactionManager(), orders, items,
                mock(JdbcBuyerLockRepository.class), mock(OrderFulfillmentService.class),
                mock(AdmissionService.class), inventory, mock(PaymentService.class),
                mock(SellerSkuService.class), mock(SettlementService.class), fixedClock(),
                Duration.ofMinutes(10), 25, Duration.ofSeconds(2),
                sweepLock());

        assertEquals(0, service.expireDueOrders());

        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(orders).findReservedOrdersExpiredBy(any(Instant.class), limit.capture());
        assertEquals(25, limit.getValue().max());
    }


    @Test
    void checkoutResponseReadsChildItemsAndReservationsInOneQueryPerCheckout() {
        BuyerCheckout checkout = new BuyerCheckout("buyer", "checkout-key", BuyerCheckoutState.RESERVED, 3_000, NOW, NOW);
        UUID checkoutId = UUID.randomUUID();
        setCheckoutId(checkout, checkoutId);
        PurchaseOrder first = new PurchaseOrder(SELLER, "buyer", "key-1", OrderState.RESERVED,
                NOW.plusSeconds(600), NOW, NOW);
        PurchaseOrder second = new PurchaseOrder(UUID.randomUUID(), "buyer", "key-2", OrderState.RESERVED,
                NOW.plusSeconds(600), NOW, NOW);
        setOrderId(first, UUID.randomUUID());
        setOrderId(second, UUID.randomUUID());
        OrderItem firstItem = new OrderItem(first, UUID.randomUUID(), SELLER, "SKU-1", "Product", 1, 1_000);
        OrderItem secondItem = new OrderItem(second, UUID.randomUUID(), second.sellerId(), "SKU-2", "Product", 2, 1_000);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        PaymentService payments = mock(PaymentService.class);
        BuyerCheckoutRepository checkouts = mock(BuyerCheckoutRepository.class);
        when(checkouts.findById(checkoutId)).thenReturn(Optional.of(checkout));
        when(orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId)).thenReturn(List.of(first, second));
        when(items.findAllByOrderIds(any())).thenReturn(List.of(firstItem, secondItem));
        when(inventory.findReservationsByOrderIds(any())).thenReturn(Map.of(
                first.orderId(), List.of(), second.orderId(), List.of()));

        CheckoutPaymentServiceImpl service = new CheckoutPaymentServiceImpl(transactionManager(), checkouts,
                orders, items, inventory, mock(OrderFulfillmentService.class), payments,
                mock(SettlementService.class), mock(SellerCouponService.class),
                fixedClock(), 100, Duration.ofSeconds(2),
                sweepLock());

        service.find(checkoutId);

        verify(items, times(1)).findAllByOrderIds(any());
        verify(inventory, times(1)).findReservationsByOrderIds(any());
        verify(items, never()).findByOrderOrderIdOrderBySkuIdAsc(any());
        verify(inventory, never()).findReservations(any());
    }



    @Test
    void batchOrderResponsesReadCheckoutPaymentOnceForAllChildOrders() {
        PurchaseOrder first = new PurchaseOrder(SELLER, "buyer", "key-1", OrderState.RESERVED,
                NOW.plusSeconds(600), NOW, NOW);
        PurchaseOrder second = new PurchaseOrder(SELLER, "buyer", "key-2", OrderState.RESERVED,
                NOW.plusSeconds(600), NOW, NOW);
        UUID checkoutId = UUID.randomUUID();
        setOrderId(first, UUID.randomUUID());
        setOrderId(second, UUID.randomUUID());
        first.setCheckoutId(checkoutId);
        second.setCheckoutId(checkoutId);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        PaymentService payments = mock(PaymentService.class);
        when(orders.findAllById(any())).thenReturn(List.of(first, second));
        when(items.findAllByOrderIds(any())).thenReturn(List.of());
        when(inventory.findReservationsByOrderIds(any())).thenReturn(Map.of());
        when(payments.findCheckout(checkoutId))
                .thenReturn(new PaymentResponse(null, checkoutId, null, PaymentState.NOT_STARTED, false));

        OrderServiceImpl service = new OrderServiceImpl(transactionManager(), orders, items,
                mock(JdbcBuyerLockRepository.class), mock(OrderFulfillmentService.class),
                mock(AdmissionService.class), inventory, payments, mock(SellerSkuService.class),
                mock(SettlementService.class), fixedClock(), Duration.ofMinutes(10), 100, Duration.ofSeconds(2),
                sweepLock());

        List<OrderResponse> responses = service.findAll(List.of(second.orderId(), first.orderId()));

        assertEquals(List.of(second.orderId(), first.orderId()),
                responses.stream().map(OrderResponse::orderId).toList());

        verify(payments, times(1)).findCheckout(checkoutId);
        verify(items, times(1)).findAllByOrderIds(any());
        verify(inventory, times(1)).findReservationsByOrderIds(any());
        verify(items, never()).findByOrderOrderIdOrderBySkuIdAsc(any());
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

    /**
     * 만료 sweep 테스트는 실제로 후보 조회까지 진행해야 하므로 락을 항상 허용합니다.
     * 락으로 인한 skip 동작 자체는 JdbcExpirySweepLockRepositoryTest가 검증합니다.
     */
    private static JdbcExpirySweepLockRepository sweepLock() {
        JdbcExpirySweepLockRepository lock = mock(JdbcExpirySweepLockRepository.class);
        when(lock.tryAcquire(anyString())).thenReturn(true);
        return lock;
    }

}
