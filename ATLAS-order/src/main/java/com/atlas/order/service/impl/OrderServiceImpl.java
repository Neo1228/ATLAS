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
import com.atlas.order.dto.response.OrderItemResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.JdbcBuyerLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.service.SettlementService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final JdbcBuyerLockRepository buyerLockRepository;
    private final TransactionTemplate transactions;
    private final AdmissionService admission;
    private final InventoryService inventory;
    private final PaymentService payments;
    private final SellerSkuService sellerSkus;
    private final SettlementService settlement;
    private final Clock clock;
    private final Duration checkoutWindow;

    public OrderServiceImpl(PlatformTransactionManager transactionManager, OrderRepository repository,
            OrderItemRepository items, JdbcBuyerLockRepository buyerLockRepository,
            OrderFulfillmentService fulfillmentService, AdmissionService admission, InventoryService inventory,
            PaymentService payments, SellerSkuService sellerSkus, SettlementService settlement, Clock clock,
            @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow) {
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
        if (payment == null || payment.state() == PaymentState.UNKNOWN
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
    public boolean isOwnedByBuyer(UUID orderId, String buyerRef) {
        if (orderId == null || buyerRef == null || buyerRef.isBlank()) return false;
        return repository.existsByOrderIdAndBuyerRef(orderId, buyerRef);
    }

    private PurchaseOrder findOrder(UUID orderId) {
        return transactions.execute(status -> requireOrder(orderId));
    }

    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public int expireDueCheckouts() {
        Instant now = clock.instant();
        List<UUID> dueOrders = repository.findReservedOrdersExpiredBy(now);
        int expired = 0;
        for (UUID orderId : dueOrders) {
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

    private OrderResponse toResponse(PurchaseOrder order) {
        List<ReservationResponse> reservations = inventory.findReservations(order.orderId());
        PaymentResponse payment = order.checkoutId() == null
                ? payments.find(order.orderId()) : payments.findCheckout(order.checkoutId());
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        UUID paymentAttemptId = order.paymentAttemptId() != null
                ? order.paymentAttemptId() : payment == null ? null : payment.attemptId();
        QueueTicketResponse ticket = order.source() == OrderSource.CAMPAIGN
                ? admission.find(order.campaignId(), order.ticketId()) : null;
        List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId());
        List<OrderItemResponse> itemResponses = orderItems.stream().map(item -> new OrderItemResponse(
                item.productId(), item.sellerId(), item.skuId(), item.productName(), item.quantity(),
                item.unitPriceWon(), item.couponDiscountWon(), item.lineTotalWon())).toList();
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
