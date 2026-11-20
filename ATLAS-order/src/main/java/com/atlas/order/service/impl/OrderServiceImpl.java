package com.atlas.order.service.impl;

import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.persistence.state.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.OrderHistoryPageResponse;
import com.atlas.order.dto.response.OrderItemResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.response.SellerOrderDetailResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.JdbcBuyerLockRepository;
import com.atlas.order.repository.JdbcExpirySweepLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.ExpirySweepPolicy;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.service.SettlementService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderServiceImpl implements OrderService {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 221;
    private static final int DEFAULT_HISTORY_PAGE_SIZE = 10;
    private static final int MAX_HISTORY_PAGE_SIZE = 100;

    private static final OwnerLockRegistry ORDER_LOCKS = new OwnerLockRegistry();

    private final OrderRepository repository;
    private final OrderItemRepository items;
    private final OrderFulfillmentService fulfillmentService;
    private final JdbcBuyerLockRepository buyerLockRepository;
    private final TransactionTemplate transactions;
    private final AdmissionService admission;
    private final InventoryService inventory;
    private final PaymentService payments;
    private final SellerSkuService sellerSkus;
    private final SettlementService settlement;
    private final Clock clock;
    private final Duration checkoutWindow;
    private final ExpirySweepPolicy expirySweep;
    private final JdbcExpirySweepLockRepository expirySweepLock;

    public OrderServiceImpl(PlatformTransactionManager transactionManager, OrderRepository repository,
            OrderItemRepository items, JdbcBuyerLockRepository buyerLockRepository,
            OrderFulfillmentService fulfillmentService, AdmissionService admission, InventoryService inventory,
            PaymentService payments, SellerSkuService sellerSkus, SettlementService settlement, Clock clock,
            @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow,
            @Value("${atlas.orders.expiry-sweep-batch-size:100}") int expirySweepBatchSize,
            @Value("${atlas.orders.expiry-sweep-time-budget:PT2S}") Duration expirySweepTimeBudget,
            JdbcExpirySweepLockRepository expirySweepLock) {
        this.repository = Objects.requireNonNull(repository);
        this.items = Objects.requireNonNull(items);
        this.buyerLockRepository = Objects.requireNonNull(buyerLockRepository);
        this.fulfillmentService = Objects.requireNonNull(fulfillmentService);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
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
    public OrderResponse startCheckout(UUID campaignId, UUID ticketId) {
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
            QueueTicketResponse ticket = admission.requireCheckoutSlot(campaignId, ticketId);
            Instant now = clock.instant();
            SellerSkuResponse sku;
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
            ReservationResponse reservation = inventory.tryReserve(new ReserveInventoryRequest(
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
    public OrderResponse submitPayment(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        if (findOrder(orderId).checkoutId() != null) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Checkout-owned Seller orders must use parent checkout payment operations");
        }
        return withOrderLock(orderId, () -> submitPaymentLocked(orderId));
    }

    private OrderResponse submitPaymentLocked(UUID orderId) {
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
    public OrderResponse completePaymentApproval(UUID orderId, String providerOrderId) {
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
    public OrderResponse reconcilePayment(UUID orderId, String idempotencyKey) {
        if (orderId == null || invalidIdempotencyKey(idempotencyKey)) throw invalidRequest();
        if (findOrder(orderId).checkoutId() != null) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Checkout-owned Seller orders must use parent checkout payment operations");
        }
        return withOrderLock(orderId, () -> reconcilePaymentLocked(orderId, idempotencyKey));
    }

    private OrderResponse reconcilePaymentLocked(UUID orderId, String idempotencyKey) {
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
    public OrderResponse cancel(UUID orderId, String idempotencyKey) {
        if (orderId == null || invalidIdempotencyKey(idempotencyKey)) throw invalidRequest();
        if (findOrder(orderId).checkoutId() != null) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Marketplace Seller orders must be cancelled through their parent checkout");
        }
        return withOrderLock(orderId, () -> cancelLocked(orderId, idempotencyKey));
    }

    private OrderResponse cancelLocked(UUID orderId, String idempotencyKey) {
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

    private OrderResponse cancelWithoutPayment(UUID orderId) {
        return transactions.execute(status -> {
            PurchaseOrder current = lockOrder(orderId);
            if (!current.state().isTerminal()) {
                releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
            }
            return toResponse(requireOrder(orderId));
        });
    }

    @Override
    public OrderResponse find(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return transactions.execute(status -> toResponse(requireOrder(orderId)));
    }

    @Override
    public SellerOrderDetailResponse findSellerOrder(UUID sellerId, UUID orderId) {
        if (sellerId == null || orderId == null) throw invalidRequest();
        return transactions.execute(status -> {
            PurchaseOrder order = requireOrder(orderId);
            if (!sellerId.equals(order.sellerId())) {
                throw new OrderException(OrderException.Code.NOT_FOUND, "Order not found");
            }
            OrderResponse detail = toResponse(order);
            if (detail.items().isEmpty()
                    || detail.items().stream().anyMatch(item -> !sellerId.equals(item.sellerId()))) {
                throw new OrderException(OrderException.Code.NOT_FOUND, "Order not found");
            }
            return new SellerOrderDetailResponse(detail.orderId(), sellerId, order.source(), detail.state(),
                    order.createdAt(), detail.checkoutExpiresAt(), detail.inventoryState(),
                    detail.fulfillmentLocationId(), detail.items(), detail.totalPriceWon(),
                    detail.sellerCouponCode(), detail.couponDiscountWon());
        });
    }

    @Override
    public List<OrderResponse> findAll(List<UUID> orderIds) {
        if (orderIds == null) throw invalidRequest();
        if (orderIds.isEmpty()) return List.of();
        List<UUID> distinct = orderIds.stream().distinct().toList();
        distinct.forEach(OrderServiceImpl::requireId);
        return transactions.execute(status -> assembleResponses(distinct));
    }

    @Override
    public OrderHistoryPageResponse findBuyerOrderHistory(String buyerRef, Integer requestedLimit,
            String cursor) {
        if (buyerRef == null || buyerRef.isBlank()) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Buyer reference is required");
        }
        int limit = requestedLimit == null ? DEFAULT_HISTORY_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_HISTORY_PAGE_SIZE) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Order history limit is out of range");
        }

        OrderHistoryCursor position = decodeOrderHistoryCursor(cursor);
        List<PurchaseOrder> fetched = Objects.requireNonNull(transactions.execute(status -> position == null
                ? repository.findByBuyerRefOrderByCreatedAtDescOrderIdDesc(buyerRef, PageRequest.of(0, limit + 1))
                : repository.findBuyerOrdersBefore(buyerRef, position.createdAt(), position.orderId(),
                        PageRequest.of(0, limit + 1))));
        boolean hasNext = fetched.size() > limit;
        List<PurchaseOrder> page = hasNext ? fetched.subList(0, limit) : fetched;
        List<OrderHistoryPageResponse.Entry> orders = page.stream()
                .map(order -> new OrderHistoryPageResponse.Entry(order.orderId(), order.checkoutId(),
                        order.sellerId(), order.source(), order.state(), order.createdAt()))
                .toList();
        String nextCursor = hasNext ? encodeOrderHistoryCursor(page.get(page.size() - 1)) : null;
        return new OrderHistoryPageResponse(orders, nextCursor);
    }

    @Override
    public OrderHistoryPageResponse findSellerOrderHistory(UUID sellerId, Integer requestedLimit,
            String cursor) {
        if (sellerId == null) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Seller is required");
        }
        int limit = requestedLimit == null ? DEFAULT_HISTORY_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_HISTORY_PAGE_SIZE) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Order history limit is out of range");
        }

        OrderHistoryCursor position = decodeOrderHistoryCursor(cursor);
        List<PurchaseOrder> fetched = Objects.requireNonNull(transactions.execute(status -> position == null
                ? repository.findBySellerIdOrderByCreatedAtDescOrderIdDesc(sellerId,
                        PageRequest.of(0, limit + 1))
                : repository.findSellerOrdersBefore(sellerId, position.createdAt(), position.orderId(),
                        PageRequest.of(0, limit + 1))));
        boolean hasNext = fetched.size() > limit;
        List<PurchaseOrder> page = hasNext ? fetched.subList(0, limit) : fetched;
        List<OrderHistoryPageResponse.Entry> orders = page.stream()
                .map(order -> new OrderHistoryPageResponse.Entry(order.orderId(), order.checkoutId(),
                        order.sellerId(), order.source(), order.state(), order.createdAt()))
                .toList();
        String nextCursor = hasNext ? encodeOrderHistoryCursor(page.get(page.size() - 1)) : null;
        return new OrderHistoryPageResponse(orders, nextCursor);
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
        if (expirySweepLock != null && !expirySweepLock.tryAcquire("orders")) return 0;
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

    private OrderResponse applyPayment(UUID orderId, PaymentResponse payment) {
        return transactions.execute(status -> {
            PurchaseOrder order = lockOrder(orderId);
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
        settlement.recordCapture(new CapturePostingRequest(
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
        List<ReservationResponse> reservations = inventory.findReservations(orderId);
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

    private List<OrderResponse> assembleResponses(List<UUID> orderIds) {
        Map<UUID, PurchaseOrder> ordersById = repository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(PurchaseOrder::orderId, order -> order));
        if (ordersById.size() != orderIds.size()) {
            throw new OrderException(OrderException.Code.NOT_FOUND, "Order not found");
        }
        List<PurchaseOrder> orders = orderIds.stream().map(ordersById::get).toList();
        Map<UUID, List<ReservationResponse>> reservationsByOrder = inventory.findReservationsByOrderIds(orderIds);
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
        Map<UUID, QueueTicketResponse> tickets = new HashMap<>();
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

    private OrderResponse toResponse(PurchaseOrder order) {
        List<ReservationResponse> reservations = inventory.findReservations(order.orderId());
        PaymentResponse payment = order.checkoutId() == null
                ? payments.find(order.orderId()) : payments.findCheckout(order.checkoutId());
        QueueTicketResponse ticket = order.source() == OrderSource.CAMPAIGN
                ? admission.find(order.campaignId(), order.ticketId()) : null;
        return toResponse(order, reservations,
                items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId()), payment, ticket);
    }

    private OrderResponse toResponse(PurchaseOrder order, List<ReservationResponse> reservations,
            List<OrderItem> orderItems, PaymentResponse payment, QueueTicketResponse ticket) {
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        UUID paymentAttemptId = order.paymentAttemptId() != null
                ? order.paymentAttemptId() : payment == null ? null : payment.attemptId();
        List<OrderItemResponse> itemResponses = orderItems.stream().map(item -> new OrderItemResponse(
                item.orderItemId(), item.productId(), item.sellerId(), item.skuId(), item.productName(),
                item.quantity(), item.unitPriceWon(), item.couponDiscountWon(), item.lineTotalWon())).toList();
        long totalPriceWon = 0;
        for (OrderItem item : orderItems) totalPriceWon = Math.addExact(totalPriceWon, item.lineTotalWon());
        ReservationStatus inventoryState = reservations.isEmpty() ? null : reservations.get(0).status();
        return new OrderResponse(order.orderId(), order.campaignId(), order.ticketId(), paymentAttemptId,
                order.state(), order.checkoutExpiresAt(), inventoryState, paymentState,
                ticket == null ? null : ticket.status(), order.source(), order.fulfillmentLocationId(),
                itemResponses, totalPriceWon, order.sellerCouponCode(), order.couponDiscountWon(),
                payment == null ? null : payment.approvalUrl());
    }

    private static boolean invalidIdempotencyKey(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH;
    }

    private static OrderHistoryCursor decodeOrderHistoryCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("Malformed cursor");
            return new OrderHistoryCursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException exception) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Invalid order history cursor");
        }
    }

    private static String encodeOrderHistoryCursor(PurchaseOrder order) {
        String value = "v1|" + order.createdAt() + "|" + order.orderId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record OrderHistoryCursor(Instant createdAt, UUID orderId) { }

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

    private record CheckoutStartOutcome(OrderResponse response, CatalogException failure) { }
}
