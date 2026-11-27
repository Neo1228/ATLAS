package com.atlas.order.service.impl;

import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.dto.request.InventoryRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.model.CheckoutSlotReleaseReason;
import com.atlas.admission.service.AdmissionService;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.model.ReservationStatus;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.model.OrderSource;
import com.atlas.order.model.OrderState;
import com.atlas.order.repository.BuyerLockRepository;
import com.atlas.order.repository.ExpirySweepLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.ExpirySweepPolicy;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.service.SettlementService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderServiceImpl implements OrderService {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 221;

    private static final OwnerLockRegistry ORDER_LOCKS = new OwnerLockRegistry();

    private final OrderRepository repository;
    private final OrderItemRepository items;
    private final OrderFulfillmentService fulfillmentService;
    private final BuyerLockRepository buyerLockRepository;
    private final TransactionTemplate transactions;
    private final OrderHistoryReader history;
    private final AdmissionService admission;
    private final InventoryService inventory;
    private final PaymentService payments;
    private final SellerSkuService sellerSkus;
    private final SettlementService settlement;
    private final Clock clock;
    private final Duration checkoutWindow;
    private final ExpirySweepPolicy expirySweep;
    private final ExpirySweepLockRepository expirySweepLock;

    public OrderServiceImpl(PlatformTransactionManager transactionManager, OrderRepository repository,
            OrderItemRepository items, BuyerLockRepository buyerLockRepository,
            OrderFulfillmentService fulfillmentService, AdmissionService admission, InventoryService inventory,
            PaymentService payments, SellerSkuService sellerSkus, SettlementService settlement, Clock clock,
            @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow,
            @Value("${atlas.orders.expiry-sweep-batch-size:100}") int expirySweepBatchSize,
            @Value("${atlas.orders.expiry-sweep-time-budget:PT2S}") Duration expirySweepTimeBudget,
            ExpirySweepLockRepository expirySweepLock) {
        this.repository = Objects.requireNonNull(repository);
        this.items = Objects.requireNonNull(items);
        this.buyerLockRepository = Objects.requireNonNull(buyerLockRepository);
        this.fulfillmentService = Objects.requireNonNull(fulfillmentService);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.history = new OrderHistoryReader(repository, this.transactions);
        this.admission = Objects.requireNonNull(admission);
        this.inventory = Objects.requireNonNull(inventory);
        this.payments = Objects.requireNonNull(payments);
        this.settlement = Objects.requireNonNull(settlement);
        this.sellerSkus = Objects.requireNonNull(sellerSkus);
        this.clock = Objects.requireNonNull(clock);
        this.checkoutWindow = Objects.requireNonNull(checkoutWindow);
        this.expirySweep = new ExpirySweepPolicy(expirySweepBatchSize, expirySweepTimeBudget);
        this.expirySweepLock = expirySweepLock;
        if (checkoutWindow.isZero() || checkoutWindow.isNegative()) {
            throw new IllegalArgumentException("checkoutWindow must be positive");
        }
    }

    @Override
    public OrderViewResponse.Order startCheckout(UUID campaignId, UUID ticketId) {
        if (campaignId == null || ticketId == null) throw invalidRequest();
        CheckoutStartOutcome outcome = transactions.execute(status -> {
            var existingOrder = repository.findByTicketId(ticketId);
            if (existingOrder.isPresent()) {
                PurchaseOrder existing = existingOrder.get();
                if (!Objects.equals(existing.campaignId(), campaignId)) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "Ticket already belongs to another campaign order");
                }
                return new CheckoutStartOutcome(toResponse(existing), null);
            }
            AdmissionResponse.QueueTicket ticket = admission.requireCheckoutSlot(campaignId, ticketId);
            Instant now = clock.instant();
            CatalogResponse.SellerSku sku;
            try {
                sku = sellerSkus.findOrderableSellerSku(ticket.sellerId(), ticket.skuId(),
                        ProductSalesChannel.CAMPAIGN, now);
            } catch (CatalogException exception) {
                if (exception.code() != CatalogException.Code.PRODUCT_NOT_ORDERABLE) throw exception;
                admission.releaseCheckoutSlot(campaignId, ticketId,
                        CheckoutSlotReleaseReason.PRODUCT_NOT_ORDERABLE);
                return new CheckoutStartOutcome(null, exception);
            }
            if (sku == null) {
                throw new CatalogException(CatalogException.Code.NOT_FOUND, "Campaign SKU not found");
            }
            admission.startCheckout(campaignId, ticketId);
            PurchaseOrder saved = repository.save(new PurchaseOrder(campaignId, ticket.sellerId(), ticketId,
                    ticket.buyerRef(), OrderState.RESERVED, now.plus(checkoutWindow), null, now, now));
            items.save(new OrderItem(saved, sku.productId(), ticket.sellerId(), sku.skuId(),
                    sku.productName(), 1, sku.effectivePriceWon()));
            InventoryResponse.Reservation reservation = inventory.tryReserve(new InventoryRequest.ReserveInventory(
                    saved.orderId(), campaignId, ticket.sellerId(), sku.skuId(), 1)).orElse(null);
            boolean reserved = reservation != null;
            if (reserved) {
                if (reservation.locationId() == null) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "Inventory reservation did not include a fulfillment location");
                }
                saved.setFulfillmentLocationId(reservation.locationId());
            }
            if (!reserved) {
                updateOrderState(saved.orderId(), OrderState.FAILED, null, clock.instant());
                admission.releaseCheckoutSlot(campaignId, ticketId,
                        CheckoutSlotReleaseReason.INVENTORY_UNAVAILABLE);
            }
            return new CheckoutStartOutcome(toResponse(requireOrder(saved.orderId())), null);
        });
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.response();
    }

    @Override
    public OrderViewResponse.Order submitPayment(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        if (findOrder(orderId).checkoutId() != null) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Checkout-owned Seller orders must use parent checkout payment operations");
        }
        return withOrderLock(orderId, () -> submitPaymentLocked(orderId));
    }

    private OrderViewResponse.Order submitPaymentLocked(UUID orderId) {
        PurchaseOrder prepared = transactions.execute(status -> {
            PurchaseOrder order = lockOrder(orderId);
            if (order.state() == OrderState.RESERVED && !clock.instant().isBefore(order.checkoutExpiresAt())) {
                expire(order);
                return requireOrder(orderId);
            }
            if (order.state() != OrderState.RESERVED && order.state() != OrderState.PAYMENT_SUBMITTED) {
                return requireOrder(orderId);
            }
            if (order.source() == OrderSource.CAMPAIGN) {
                buyerLockRepository.lockBuyer(order.campaignId() + ":" + order.buyerRef());
                if (repository.hasOtherUnresolvedPayment(order)) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "Another payment for this buyer is unresolved");
                }
            }
            if (order.state() == OrderState.RESERVED) {
                updateOrderState(orderId, OrderState.PAYMENT_SUBMITTED, order.paymentAttemptId(), clock.instant());
            }
            PurchaseOrder submitted = requireOrder(orderId);
            if (submitted.state() == OrderState.PAYMENT_SUBMITTED) {
                PaymentResponse attempt = payments.prepareInitial(
                        orderId, BigDecimal.valueOf(paymentAmountWon(orderId)));
                if (!Objects.equals(submitted.paymentAttemptId(), attempt.attemptId())) {
                    updateOrderState(orderId, OrderState.PAYMENT_SUBMITTED, attempt.attemptId(), clock.instant());
                }
            }
            return requireOrder(orderId);
        });
        if (prepared.state() != OrderState.PAYMENT_SUBMITTED) return find(orderId);
        // PayPal HTTP 호출은 업무 DB transaction 밖에서 수행.
        PaymentResponse payment;
        try {
            payment = payments.submitInitial(orderId, BigDecimal.valueOf(paymentAmountWon(orderId)));
        } catch (PaymentException exception) {
            if (exception.code() == PaymentException.Code.PAYMENT_PROVIDER_DISABLED) {
                PaymentResponse attempt = payments.find(orderId);
                if (attempt == null || attempt.state() == PaymentState.NOT_STARTED) {
                    transactions.execute(status -> {
                        PurchaseOrder current = lockOrder(orderId);
                        if (current.state() == OrderState.PAYMENT_SUBMITTED) {
                            current.updateState(OrderState.RESERVED, current.paymentAttemptId(), clock.instant());
                        }
                        return null;
                    });
                }
            }
            throw exception;
        }
        if (payment.state() == PaymentState.DECLINED && payment.authorizationActive()) {
            payment = payments.voidAuthorization(orderId, "submit-void:" + orderId);
        }
        return applyPayment(orderId, payment);
    }

    @Override
    public OrderViewResponse.Order completePaymentApproval(UUID orderId, String providerOrderId) {
        PaymentResponse payment = payments.completeApproval(orderId, providerOrderId);
        if (payment.state() == PaymentState.AUTHORIZATION_PENDING
                || payment.state() == PaymentState.CAPTURE_PENDING
                || payment.state() == PaymentState.UNKNOWN
                || payment.state() == PaymentState.VOID_PENDING) {
            return applyPayment(orderId, payment);
        }
        return reconcilePayment(orderId, "paypal-approval:" + providerOrderId);
    }

    @Override
    public OrderViewResponse.Order reconcilePayment(UUID orderId, String idempotencyKey) {
        if (orderId == null || invalidIdempotencyKey(idempotencyKey)) throw invalidRequest();
        if (findOrder(orderId).checkoutId() != null) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Checkout-owned Seller orders must use parent checkout payment operations");
        }
        return withOrderLock(orderId, () -> reconcilePaymentLocked(orderId, idempotencyKey));
    }

    private OrderViewResponse.Order reconcilePaymentLocked(UUID orderId, String idempotencyKey) {
        PurchaseOrder order = findOrder(orderId);
        PaymentResponse payment = payments.find(orderId);
        if (payment == null && order.state() != OrderState.PAYMENT_SUBMITTED
                && order.state() != OrderState.CANCEL_PENDING && order.state() != OrderState.PAYMENT_VOID_PENDING) {
            return find(orderId);
        }
        if (payment == null || payment.state() == PaymentState.NOT_STARTED
                || payment.state() == PaymentState.UNKNOWN
                || payment.state() == PaymentState.AUTHORIZATION_PENDING
                || payment.state() == PaymentState.CAPTURE_PENDING
                || payment.state() == PaymentState.AUTHORIZED
                || payment.state() == PaymentState.VOID_PENDING) {
            payment = order.state() == OrderState.CANCEL_PENDING || order.state() == OrderState.PAYMENT_VOID_PENDING
                    ? payments.reconcileForCancellation(orderId, "reconcile-cancel:" + idempotencyKey)
                    : payments.reconcile(orderId, idempotencyKey);
        }
        if (payment.state() == PaymentState.DECLINED && payment.authorizationActive()) {
            payment = payments.voidAuthorization(orderId, idempotencyKey + ":void");
        } else if (payment.state() == PaymentState.AUTHORIZED
                && (order.state() == OrderState.CANCEL_PENDING || order.state() == OrderState.PAYMENT_VOID_PENDING)) {
            payment = payments.voidAuthorization(orderId, idempotencyKey + ":void");
        }
        return applyPayment(orderId, payment);
    }

    @Override
    public OrderViewResponse.Order cancel(UUID orderId, String idempotencyKey) {
        if (orderId == null || invalidIdempotencyKey(idempotencyKey)) throw invalidRequest();
        if (findOrder(orderId).checkoutId() != null) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Marketplace Seller orders must be cancelled through their parent checkout");
        }
        return withOrderLock(orderId, () -> cancelLocked(orderId, idempotencyKey));
    }

    private OrderViewResponse.Order cancelLocked(UUID orderId, String idempotencyKey) {
        PurchaseOrder current = transactions.execute(status -> {
            PurchaseOrder order = lockOrder(orderId);
            if (order.state() == OrderState.PAID) {
                // 결제 완료 주문은 취소로 되돌릴 수 없다. 200 무변경 응답은 구매자에게 취소 성공으로 읽히므로
                // checkout 취소와 같은 CONFLICT 로 거절하고 Seller 환불 경로로 안내한다.
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Paid orders use the Seller order refund path");
            }
            // CANCELLED 는 같은 취소의 재시도이고, EXPIRED/FAILED 는 재고·대기열 슬롯·결제 승인이 이미 해제되어
            // 되돌릴 것이 없다. 취소 요청의 목적(주문이 진행되지 않음)이 이미 충족되므로 checkout 취소와 같이
            // 현재 상태를 그대로 반환하는 멱등 no-op 으로 둔다.
            if (order.state().isTerminal()) return order;
            if (order.state() == OrderState.RESERVED && !clock.instant().isBefore(order.checkoutExpiresAt())) {
                expire(order);
                return requireOrder(orderId);
            }
            if (order.state() == OrderState.RESERVED) {
                releaseWithoutPayment(order, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
                return requireOrder(orderId);
            }
            if (order.state() != OrderState.CANCEL_PENDING) {
                // 취소 요청과 캡처 펜스를 같은 트랜잭션에 기록한다. 복구·승인 콜백이 이 뒤에
                // AUTHORIZED 결제의 새 capture 를 시작하지 못한다(이미 시작된 capture 는 계속 복구).
                payments.prohibitNewOrderCapture(orderId);
                updateOrderState(orderId, OrderState.CANCEL_PENDING, order.paymentAttemptId(), clock.instant());
            }
            return requireOrder(orderId);
        });
        if (current.state().isTerminal()) return find(orderId);
        PaymentResponse payment = payments.find(orderId);
        if (payment == null) return cancelWithoutPayment(orderId);
        payment = payments.reconcileForCancellation(orderId, idempotencyKey);
        if (payment.state() == PaymentState.AUTHORIZED
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidAuthorization(orderId, idempotencyKey + ":void");
        }
        return applyPayment(orderId, payment);
    }

    private OrderViewResponse.Order cancelWithoutPayment(UUID orderId) {
        return transactions.execute(status -> {
            PurchaseOrder current = lockOrder(orderId);
            if (!current.state().isTerminal()) {
                releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
            }
            return toResponse(requireOrder(orderId));
        });
    }

    @Override
    public OrderViewResponse.Order find(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return transactions.execute(status -> toResponse(requireOrder(orderId)));
    }

    @Override
    public OrderViewResponse.SellerOrderDetail findSellerOrder(UUID sellerId, UUID orderId) {
        if (sellerId == null || orderId == null) throw invalidRequest();
        return transactions.execute(status -> {
            PurchaseOrder order = requireOrder(orderId);
            if (!sellerId.equals(order.sellerId())) {
                throw new OrderException(OrderException.Code.NOT_FOUND, "Order not found");
            }
            OrderViewResponse.Order detail = toResponse(order);
            if (detail.items().isEmpty()
                    || detail.items().stream().anyMatch(item -> !sellerId.equals(item.sellerId()))) {
                throw new OrderException(OrderException.Code.NOT_FOUND, "Order not found");
            }
            return new OrderViewResponse.SellerOrderDetail(detail.orderId(), sellerId, order.source(), detail.state(),
                    order.createdAt(), detail.checkoutExpiresAt(), detail.inventoryState(),
                    detail.fulfillmentLocationId(), detail.items(), detail.totalPriceWon(),
                    detail.sellerCouponCode(), detail.couponDiscountWon());
        });
    }

    @Override
    public List<OrderViewResponse.Order> findAll(List<UUID> orderIds) {
        if (orderIds == null) throw invalidRequest();
        if (orderIds.isEmpty()) return List.of();
        List<UUID> distinct = orderIds.stream().distinct().toList();
        distinct.forEach(OrderServiceImpl::requireId);
        return transactions.execute(status -> assembleResponses(distinct));
    }

    @Override
    public OrderViewResponse.HistoryPage findBuyerOrderHistory(String buyerRef, Integer requestedLimit,
            String cursor) {
        return history.buyerHistory(buyerRef, requestedLimit, cursor);
    }

    @Override
    public OrderViewResponse.HistoryPage findSellerOrderHistory(UUID sellerId, Integer requestedLimit,
            String cursor) {
        return history.sellerHistory(sellerId, requestedLimit, cursor);
    }

    @Override
    public Optional<UUID> findDeliveredOrderItemId(String buyerRef, UUID productId) {
        return repository.findDeliveredOrderItemId(buyerRef, productId);
    }

    @Override
    public boolean isOwnedByBuyer(UUID orderId, String buyerRef) {
        if (orderId == null || buyerRef == null || buyerRef.isBlank()) return false;
        return repository.existsByOrderIdAndBuyerRef(orderId, buyerRef);
    }

    private PurchaseOrder findOrder(UUID orderId) {
        return transactions.execute(status -> requireOrder(orderId));
    }

    @Override
    public int expireDueOrders() {
        // Only one replica runs this sweep per tick; the others skip instead of
        // duplicating the candidate scan and racing for the same row locks.
        if (expirySweepLock == null) return sweepExpiredOrders();
        return expirySweepLock.runExclusive("orders", this::sweepExpiredOrders).orElse(0);
    }

    private int sweepExpiredOrders() {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + expirySweep.timeBudget().toNanos();
        List<UUID> dueOrders = repository.findReservedOrdersExpiredBy(now, Limit.of(expirySweep.batchSize()));
        int expired = 0;
        for (UUID orderId : dueOrders) {
            if (System.nanoTime() >= deadline) break;
            Integer changed = transactions.execute(status -> {
                PurchaseOrder order = lockOrder(orderId);
                if (order.state() != OrderState.RESERVED || clock.instant().isBefore(order.checkoutExpiresAt())) return 0;
                expire(order);
                return 1;
            });
            expired += changed == null ? 0 : changed;
        }
        return expired;
    }

    private OrderViewResponse.Order applyPayment(UUID orderId, PaymentResponse snapshot) {
        return transactions.execute(status -> {
            PurchaseOrder order = lockOrder(orderId);
            // 잠금 밖에서 받은 스냅숏 대신 주문 행 잠금 아래에서 결제 상태를 다시 읽는다. 다른 인스턴스가 그
            // 사이 PAID를 커밋했으면 스냅숏은 오래된 값이다(EXP-118 H2, CheckoutPaymentServiceImpl과 같은 이유).
            PaymentResponse payment = Objects.requireNonNullElse(payments.find(orderId), snapshot);
            if (order.source() == OrderSource.CAMPAIGN) {
                buyerLockRepository.lockBuyer(order.campaignId() + ":" + order.buyerRef());
                admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                        CheckoutSlotReleaseReason.INITIAL_AUTHORIZE_SUBMITTED);
            }
            OrderState next = switch (payment.state()) {
                case CAPTURED -> {
                    recordCapture(order, payment);
                    if (order.state() != OrderState.PAID) inventory.claimAll(orderId);
                    yield OrderState.PAID;
                }
                case DECLINED -> {
                    if (payment.authorizationActive()) yield OrderState.PAYMENT_VOID_PENDING;
                    inventory.releaseAll(orderId);
                    yield order.state() == OrderState.CANCEL_PENDING ? OrderState.CANCELLED : OrderState.FAILED;
                }
                case VOIDED -> {
                    inventory.releaseAll(orderId);
                    yield order.state() == OrderState.CANCEL_PENDING ? OrderState.CANCELLED : OrderState.FAILED;
                }
                case VOID_PENDING -> order.state() == OrderState.CANCEL_PENDING
                        ? OrderState.CANCEL_PENDING : OrderState.PAYMENT_VOID_PENDING;
                case UNKNOWN -> order.state() == OrderState.CANCEL_PENDING
                        ? OrderState.CANCEL_PENDING : OrderState.PAYMENT_UNKNOWN;
                case NOT_STARTED -> order.state() == OrderState.CANCEL_PENDING
                        ? OrderState.CANCEL_PENDING : OrderState.PAYMENT_SUBMITTED;
                case AUTHORIZATION_PENDING, AUTHORIZED, CAPTURE_PENDING -> order.state() == OrderState.CANCEL_PENDING
                        ? OrderState.CANCEL_PENDING : OrderState.PAYMENT_PENDING;
            };
            updateOrderState(orderId, next, payment.attemptId(), clock.instant());
            if (next == OrderState.PAID) fulfillmentService.initialize(orderId);
            if (next.isTerminal() && !payment.authorizationActive() && payment.attemptId() != null) {
                payments.markOwnerApplied(payment.attemptId());
            }
            return toResponse(requireOrder(orderId));
        });
    }

    private void recordCapture(PurchaseOrder order, PaymentResponse payment) {
        if (payment.attemptId() == null) {
            throw new OrderException(OrderException.Code.CONFLICT, "Captured payment has no attempt id");
        }
        List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId());
        if (orderItems.isEmpty()) {
            throw new OrderException(OrderException.Code.CONFLICT, "Captured order has no item snapshot");
        }
        UUID sellerId = orderItems.get(0).sellerId();
        long grossAmountWon = 0;
        for (OrderItem item : orderItems) {
            if (!sellerId.equals(item.sellerId())) {
                throw new OrderException(OrderException.Code.CONFLICT, "Captured order contains multiple sellers");
            }
            grossAmountWon = Math.addExact(grossAmountWon, item.lineTotalWon());
        }
        settlement.recordCapture(new SettlementPostingRequest.Capture(
                order.orderId(), payment.attemptId(), sellerId, grossAmountWon));
    }

    private void expire(PurchaseOrder order) {
        if (order.source() == OrderSource.CAMPAIGN) {
            admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                    CheckoutSlotReleaseReason.CHECKOUT_WINDOW_EXPIRED);
        }
        releaseReservations(order.orderId());
        updateOrderState(order.orderId(), OrderState.EXPIRED, order.paymentAttemptId(), clock.instant());
    }

    private void releaseWithoutPayment(PurchaseOrder order, OrderState state, CheckoutSlotReleaseReason reason) {
        if (order.source() == OrderSource.CAMPAIGN) admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(), reason);
        releaseReservations(order.orderId());
        updateOrderState(order.orderId(), state, order.paymentAttemptId(), clock.instant());
    }

    private void releaseReservations(UUID orderId) {
        List<InventoryResponse.Reservation> reservations = inventory.findReservations(orderId);
        if (reservations.stream().anyMatch(reservation -> reservation.status() == ReservationStatus.RESERVED)) {
            inventory.releaseAll(orderId);
        }
    }

    private PurchaseOrder requireOrder(UUID orderId) {
        return repository.findById(orderId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Order not found"));
    }

    private PurchaseOrder lockOrder(UUID orderId) {
        return repository.findWithLockByOrderId(orderId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Order not found"));
    }

    private void updateOrderState(UUID orderId, OrderState state, UUID paymentAttemptId, Instant updatedAt) {
        requireOrder(orderId).updateState(state, paymentAttemptId, updatedAt);
    }

    private List<OrderViewResponse.Order> assembleResponses(List<UUID> orderIds) {
        Map<UUID, PurchaseOrder> ordersById = repository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(PurchaseOrder::orderId, order -> order));
        if (ordersById.size() != orderIds.size()) {
            throw new OrderException(OrderException.Code.NOT_FOUND, "Order not found");
        }
        List<PurchaseOrder> orders = orderIds.stream().map(ordersById::get).toList();
        Map<UUID, List<InventoryResponse.Reservation>> reservationsByOrder = inventory.findReservationsByOrderIds(orderIds);
        Map<UUID, List<OrderItem>> itemsByOrder = items.findAllByOrderIds(orderIds).stream()
                .collect(Collectors.groupingBy(item -> item.order().orderId()));
        Map<UUID, PaymentResponse> checkoutPayments = new HashMap<>();
        Map<UUID, PaymentResponse> orderPayments = new HashMap<>();
        for (PurchaseOrder order : orders) {
            if (order.checkoutId() == null) {
                orderPayments.put(order.orderId(), payments.find(order.orderId()));
            } else {
                checkoutPayments.computeIfAbsent(order.checkoutId(), payments::findCheckout);
            }
        }
        Map<UUID, AdmissionResponse.QueueTicket> tickets = new HashMap<>();
        for (PurchaseOrder order : orders) {
            if (order.source() == OrderSource.CAMPAIGN) {
                tickets.computeIfAbsent(order.ticketId(), key -> admission.find(order.campaignId(), key));
            }
        }
        return orders.stream()
                .map(order -> toResponse(order,
                        reservationsByOrder.getOrDefault(order.orderId(), List.of()),
                        itemsByOrder.getOrDefault(order.orderId(), List.of()),
                        order.checkoutId() == null
                                ? orderPayments.get(order.orderId()) : checkoutPayments.get(order.checkoutId()),
                        order.source() == OrderSource.CAMPAIGN ? tickets.get(order.ticketId()) : null))
                .toList();
    }

    private static void requireId(UUID orderId) {
        if (orderId == null) throw invalidRequest();
    }

    private OrderViewResponse.Order toResponse(PurchaseOrder order) {
        List<InventoryResponse.Reservation> reservations = inventory.findReservations(order.orderId());
        PaymentResponse payment = order.checkoutId() == null
                ? payments.find(order.orderId()) : payments.findCheckout(order.checkoutId());
        AdmissionResponse.QueueTicket ticket = order.source() == OrderSource.CAMPAIGN
                ? admission.find(order.campaignId(), order.ticketId()) : null;
        return toResponse(order, reservations,
                items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId()), payment, ticket);
    }

    private OrderViewResponse.Order toResponse(PurchaseOrder order, List<InventoryResponse.Reservation> reservations,
            List<OrderItem> orderItems, PaymentResponse payment, AdmissionResponse.QueueTicket ticket) {
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        UUID paymentAttemptId = order.paymentAttemptId() != null
                ? order.paymentAttemptId() : payment == null ? null : payment.attemptId();
        List<OrderViewResponse.Item> itemResponses = orderItems.stream().map(item -> new OrderViewResponse.Item(
                item.orderItemId(), item.productId(), item.sellerId(), item.skuId(), item.productName(),
                item.quantity(), item.unitPriceWon(), item.couponDiscountWon(), item.lineTotalWon())).toList();
        long totalPriceWon = 0;
        for (OrderItem item : orderItems) totalPriceWon = Math.addExact(totalPriceWon, item.lineTotalWon());
        ReservationStatus inventoryState = reservations.isEmpty() ? null : reservations.get(0).status();
        return new OrderViewResponse.Order(order.orderId(), order.campaignId(), order.ticketId(), paymentAttemptId,
                order.state(), order.checkoutExpiresAt(), inventoryState, paymentState,
                ticket == null ? null : ticket.status(), order.source(), order.fulfillmentLocationId(),
                itemResponses, totalPriceWon, order.sellerCouponCode(), order.couponDiscountWon(),
                payment == null ? null : payment.approvalUrl());
    }

    private static boolean invalidIdempotencyKey(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH;
    }

    private static OrderException invalidRequest() {
        return new OrderException(OrderException.Code.INVALID_REQUEST, "orderId is required");
    }

    private <T> T withOrderLock(UUID orderId, Supplier<T> work) {
        return ORDER_LOCKS.withLock(orderId, work);
    }

    private long paymentAmountWon(UUID orderId) {
        long total = 0;
        for (OrderItem item : items.findByOrderOrderIdOrderBySkuIdAsc(orderId)) {
            total = Math.addExact(total, item.lineTotalWon());
        }
        if (total <= 0) throw new OrderException(OrderException.Code.INVALID_REQUEST, "Order has no payable amount");
        return total;
    }

    private record CheckoutStartOutcome(OrderViewResponse.Order response, CatalogException failure) { }
}
