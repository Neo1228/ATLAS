package com.atlas.order.service.impl;

import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
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
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.service.PaymentService;
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
    private static final Object[] ORDER_LOCKS = createOrderLocks();

    private final OrderRepository repository;
    private final OrderItemRepository items;
    private final JdbcBuyerLockRepository buyerLockRepository;
    private final TransactionTemplate transactions;
    private final AdmissionService admission;
    private final InventoryService inventory;
    private final PaymentService payments;
    private final SellerSkuService sellerSkus;
    private final Clock clock;
    private final Duration checkoutWindow;

    public OrderServiceImpl(PlatformTransactionManager transactionManager, OrderRepository repository,
                            OrderItemRepository items, JdbcBuyerLockRepository buyerLockRepository,
                            AdmissionService admission, InventoryService inventory, PaymentService payments,
                            SellerSkuService sellerSkus, Clock clock,
                            @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow) {
        this.repository = Objects.requireNonNull(repository);
        this.items = Objects.requireNonNull(items);
        this.buyerLockRepository = Objects.requireNonNull(buyerLockRepository);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.admission = Objects.requireNonNull(admission);
        this.inventory = Objects.requireNonNull(inventory);
        this.payments = Objects.requireNonNull(payments);
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
        return transactions.execute(status -> {
            PurchaseOrder existing = repository.findByTicketOrNull(ticketId);
            if (existing != null) {
                if (!Objects.equals(existing.campaignId(), campaignId)) {
                    throw new OrderException(OrderException.Code.CONFLICT, "Ticket already belongs to another campaign order");
                }
                return toResponse(existing);
            }
            QueueTicketResponse ticket = admission.requireCheckoutSlot(campaignId, ticketId);
            admission.startCheckout(campaignId, ticketId);
            SellerSkuResponse sku = sellerSkus.find(ticket.sellerId(), ticket.skuId());
            Instant now = clock.instant();
            PurchaseOrder saved = repository.save(new PurchaseOrder(campaignId, ticket.sellerId(), ticketId,
                    ticket.buyerRef(), OrderState.RESERVED, now.plus(checkoutWindow), null, now, now));
            items.save(new OrderItem(saved, sku.productId(), ticket.sellerId(), sku.skuId(),
                    sku.productName(), 1, sku.priceWon()));
            boolean reserved = inventory.tryReserve(new ReserveInventoryRequest(
                    saved.orderId(), campaignId, ticket.sellerId(), sku.skuId(), 1)).isPresent();
            if (!reserved) {
                repository.updateState(saved.orderId(), OrderState.FAILED, null, clock.instant());
                admission.releaseCheckoutSlot(campaignId, ticketId,
                        CheckoutSlotReleaseReason.INVENTORY_UNAVAILABLE);
            }
            return toResponse(repository.requireById(saved.orderId()));
        });
    }

    @Override
    public OrderResponse submitPayment(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return withOrderLock(orderId, () -> submitPaymentLocked(orderId));
    }

    private OrderResponse submitPaymentLocked(UUID orderId) {
        PurchaseOrder prepared = transactions.execute(status -> {
            PurchaseOrder order = repository.lockById(orderId);
            if (order.state() == OrderState.RESERVED && !clock.instant().isBefore(order.checkoutExpiresAt())) {
                expire(order);
                return repository.requireById(orderId);
            }
            if (order.state() != OrderState.RESERVED && order.state() != OrderState.PAYMENT_SUBMITTED) {
                return repository.requireById(orderId);
            }
            if (order.source() == OrderSource.CAMPAIGN) {
                buyerLockRepository.lockBuyer(order.campaignId() + ":" + order.buyerRef());
                if (repository.hasOtherUnresolvedPayment(order)) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "Another payment for this buyer is unresolved");
                }
            }
            if (order.state() == OrderState.RESERVED) {
                repository.updateState(orderId, OrderState.PAYMENT_SUBMITTED, order.paymentAttemptId(), clock.instant());
            }
            return repository.requireById(orderId);
        });
        if (prepared.state() != OrderState.PAYMENT_SUBMITTED) return find(orderId);
        // The external PSP call remains outside the database transaction.
        PaymentResponse payment = payments.submitInitial(orderId);
        if (payment.state() == PaymentState.DECLINED && payment.authorizationActive()) {
            payment = payments.voidAuthorization(orderId);
        }
        return applyPayment(orderId, payment);
    }

    @Override
    public OrderResponse reconcilePayment(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return withOrderLock(orderId, () -> reconcilePaymentLocked(orderId));
    }

    private OrderResponse reconcilePaymentLocked(UUID orderId) {
        PurchaseOrder order = findOrder(orderId);
        PaymentResponse payment = payments.find(orderId);
        if (payment == null && order.state() != OrderState.PAYMENT_SUBMITTED
                && order.state() != OrderState.CANCEL_PENDING && order.state() != OrderState.PAYMENT_VOID_PENDING) {
            return find(orderId);
        }
        if (payment == null || payment.state() == PaymentState.UNKNOWN) {
            payment = order.state() == OrderState.CANCEL_PENDING || order.state() == OrderState.PAYMENT_VOID_PENDING
                    ? payments.reconcileForCancellation(orderId) : payments.reconcile(orderId);
        }
        if (payment.state() == PaymentState.DECLINED && payment.authorizationActive()) {
            payment = payments.voidAuthorization(orderId);
        } else if (payment.state() == PaymentState.AUTHORIZED
                && (order.state() == OrderState.CANCEL_PENDING || order.state() == OrderState.PAYMENT_VOID_PENDING)) {
            payment = payments.voidAuthorization(orderId);
        }
        return applyPayment(orderId, payment);
    }

    @Override
    public OrderResponse cancel(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return withOrderLock(orderId, () -> cancelLocked(orderId));
    }

    private OrderResponse cancelLocked(UUID orderId) {
        PurchaseOrder current = transactions.execute(status -> {
            PurchaseOrder order = repository.lockById(orderId);
            if (terminal(order.state())) return order;
            if (order.state() == OrderState.RESERVED && !clock.instant().isBefore(order.checkoutExpiresAt())) {
                expire(order);
                return repository.requireById(orderId);
            }
            if (order.state() == OrderState.RESERVED) {
                releaseWithoutPayment(order, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
                return repository.requireById(orderId);
            }
            if (order.state() != OrderState.CANCEL_PENDING) {
                repository.updateState(orderId, OrderState.CANCEL_PENDING, order.paymentAttemptId(), clock.instant());
            }
            return repository.requireById(orderId);
        });
        if (terminal(current.state())) return find(orderId);
        PaymentResponse payment = payments.find(orderId);
        if (payment == null) return cancelWithoutPayment(orderId);
        payment = payments.reconcileForCancellation(orderId);
        if (payment.state() == PaymentState.AUTHORIZED
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidAuthorization(orderId);
        }
        return applyPayment(orderId, payment);
    }

    private OrderResponse cancelWithoutPayment(UUID orderId) {
        return transactions.execute(status -> {
            PurchaseOrder current = repository.lockById(orderId);
            if (!terminal(current.state())) {
                releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
            }
            return toResponse(repository.requireById(orderId));
        });
    }

    @Override
    public OrderResponse find(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return transactions.execute(status -> toResponse(repository.requireById(orderId)));
    }

    private PurchaseOrder findOrder(UUID orderId) {
        return transactions.execute(status -> repository.requireById(orderId));
    }

    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public int expireDueCheckouts() {
        Instant now = clock.instant();
        List<UUID> dueOrders = repository.findReservedOrdersExpiredBy(now);
        int expired = 0;
        for (UUID orderId : dueOrders) {
            Integer changed = transactions.execute(status -> {
                PurchaseOrder order = repository.lockById(orderId);
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
            PurchaseOrder order = repository.lockById(orderId);
            if (order.source() == OrderSource.CAMPAIGN) {
                buyerLockRepository.lockBuyer(order.campaignId() + ":" + order.buyerRef());
                admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                        CheckoutSlotReleaseReason.INITIAL_AUTHORIZE_SUBMITTED);
            }
            OrderState next = switch (payment.state()) {
                case CAPTURED -> { inventory.claimAll(orderId); yield OrderState.PAID; }
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
            repository.updateState(orderId, next, payment.attemptId(), clock.instant());
            return toResponse(repository.requireById(orderId));
        });
    }

    private void expire(PurchaseOrder order) {
        if (order.source() == OrderSource.CAMPAIGN) {
            admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                    CheckoutSlotReleaseReason.CHECKOUT_WINDOW_EXPIRED);
        }
        releaseReservations(order.orderId());
        repository.updateState(order.orderId(), OrderState.EXPIRED, order.paymentAttemptId(), clock.instant());
    }

    private void releaseWithoutPayment(PurchaseOrder order, OrderState state, CheckoutSlotReleaseReason reason) {
        if (order.source() == OrderSource.CAMPAIGN) admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(), reason);
        releaseReservations(order.orderId());
        repository.updateState(order.orderId(), state, order.paymentAttemptId(), clock.instant());
    }

    private void releaseReservations(UUID orderId) {
        List<ReservationResponse> reservations = inventory.findReservations(orderId);
        if (reservations.stream().anyMatch(reservation -> reservation.status() == ReservationStatus.RESERVED)) {
            inventory.releaseAll(orderId);
        }
    }

    private OrderResponse toResponse(PurchaseOrder order) {
        List<ReservationResponse> reservations = inventory.findReservations(order.orderId());
        PaymentResponse payment = payments.find(order.orderId());
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        UUID paymentAttemptId = order.paymentAttemptId() != null
                ? order.paymentAttemptId() : payment == null ? null : payment.attemptId();
        QueueTicketResponse ticket = order.source() == OrderSource.CAMPAIGN
                ? admission.find(order.campaignId(), order.ticketId()) : null;
        List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId());
        List<OrderItemResponse> itemResponses = orderItems.stream().map(item -> new OrderItemResponse(
                item.productId(), item.sellerId(), item.skuId(), item.productName(), item.quantity(),
                item.unitPriceWon())).toList();
        long totalPriceWon = 0;
        for (OrderItem item : orderItems) totalPriceWon = Math.addExact(totalPriceWon, item.lineTotalWon());
        ReservationStatus inventoryState = reservations.isEmpty() ? null : reservations.get(0).status();
        return new OrderResponse(order.orderId(), order.campaignId(), order.ticketId(), paymentAttemptId,
                order.state(), order.checkoutExpiresAt(), inventoryState, paymentState,
                ticket == null ? null : ticket.status(), order.source(), itemResponses, totalPriceWon);
    }

    private static OrderException invalidRequest() {
        return new OrderException(OrderException.Code.INVALID_REQUEST, "orderId is required");
    }

    private static boolean terminal(OrderState state) {
        return state == OrderState.PAID || state == OrderState.FAILED
                || state == OrderState.EXPIRED || state == OrderState.CANCELLED;
    }

    private <T> T withOrderLock(UUID orderId, Supplier<T> work) {
        synchronized (ORDER_LOCKS[Math.floorMod(orderId.hashCode(), ORDER_LOCKS.length)]) { return work.get(); }
    }

    private static Object[] createOrderLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }
}
