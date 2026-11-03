package com.atlas.order.service.impl;

import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.dto.request.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.order.exception.OrderException;
import com.atlas.order.service.OrderService;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.repository.JdbcBuyerLockRepository;
import com.atlas.order.persistence.repository.OrderRepository;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.dto.response.PaymentResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderServiceImpl implements OrderService {

    private static final Object[] ORDER_LOCKS = createOrderLocks();

    private final OrderRepository repository;
    private final JdbcBuyerLockRepository buyerLockRepository;
    private final TransactionTemplate transactions;
    private final AdmissionService admission;
    private final InventoryService inventory;
    private final PaymentService payments;
    private final Clock clock;
    private final Duration checkoutWindow;
    private final Supplier<UUID> ids;

    @Autowired
    public OrderServiceImpl(PlatformTransactionManager transactionManager, OrderRepository repository,
            JdbcBuyerLockRepository buyerLockRepository, AdmissionService admission, InventoryService inventory,
            PaymentService payments, Clock clock, @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow) {
        this(repository, buyerLockRepository, transactionManager, admission, inventory, payments, clock,
                checkoutWindow, UUID::randomUUID);
    }

    OrderServiceImpl(OrderRepository repository, JdbcBuyerLockRepository buyerLockRepository,
            PlatformTransactionManager transactionManager, AdmissionService admission, InventoryService inventory,
            PaymentService payments, Clock clock, Duration checkoutWindow, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.buyerLockRepository = Objects.requireNonNull(buyerLockRepository);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.admission = Objects.requireNonNull(admission);
        this.inventory = Objects.requireNonNull(inventory);
        this.payments = Objects.requireNonNull(payments);
        this.clock = Objects.requireNonNull(clock);
        this.checkoutWindow = Objects.requireNonNull(checkoutWindow);
        this.ids = Objects.requireNonNull(ids);
        if (checkoutWindow.isZero() || checkoutWindow.isNegative()) {
            throw new IllegalArgumentException("checkoutWindow must be positive");
        }
    }

    @Override
    public OrderResponse startCheckout(UUID campaignId, UUID ticketId) {
        if (campaignId == null || ticketId == null) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "campaignId and ticketId are required");
        }
        return transactions.execute(status -> {
            PurchaseOrder existing = repository.findByTicketOrNull(ticketId);
            if (existing != null) {
                if (!existing.campaignId().equals(campaignId)) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "ticket already belongs to a different campaign");
                }
                return toResponse(existing);
            }
            QueueTicketResponse ticket = admission.requireCheckoutSlot(campaignId, ticketId);
            ticket = admission.startCheckout(campaignId, ticketId);
            existing = repository.findByTicketOrNull(ticketId);
            if (existing != null) return toResponse(existing);

            UUID orderId = ids.get();
            Instant now = clock.instant();
            Instant expiresAt = now.plus(checkoutWindow);
            boolean reserved = inventory.tryReserve(new ReserveInventoryRequest(
                    orderId, campaignId, ticket.sellerId(), ticket.skuId(), 1)).isPresent();
            OrderState state = reserved ? OrderState.RESERVED : OrderState.FAILED;
            if (!reserved) {
                admission.releaseCheckoutSlot(campaignId, ticketId, CheckoutSlotReleaseReason.INVENTORY_UNAVAILABLE);
            }
            repository.save(new PurchaseOrder(orderId, campaignId, ticket.sellerId(), ticketId, ticket.buyerRef(), ticket.skuId(),
                    state, expiresAt, null, now, now));
            return toResponse(repository.requireById(orderId));
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
                return order;
            }
            buyerLockRepository.lockBuyer(order.campaignId() + ":" + order.buyerRef());
            if (repository.hasOtherUnresolvedPayment(order)) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "another payment for this buyer and campaign is unresolved");
            }
            if (order.state() == OrderState.RESERVED) {
                repository.updateState(orderId, OrderState.PAYMENT_SUBMITTED, order.paymentAttemptId(), clock.instant());
            }
            return repository.requireById(orderId);
        });
        if (prepared.state() != OrderState.PAYMENT_SUBMITTED) return find(orderId);

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
        PurchaseOrder order = repository.requireById(orderId);
        PaymentResponse existing = payments.find(orderId);
        if (existing == null && order.state() != OrderState.PAYMENT_SUBMITTED) return find(orderId);
        boolean cancellation = order.state() == OrderState.CANCEL_PENDING
                || order.state() == OrderState.PAYMENT_VOID_PENDING;
        PaymentResponse payment = existing == null ? payments.submitInitial(orderId)
                : cancellation ? payments.reconcileForCancellation(orderId) : payments.reconcile(orderId);
        if (payment.state() == PaymentState.DECLINED && payment.authorizationActive()) {
            payment = payments.voidAuthorization(orderId);
        } else if (payment.state() == PaymentState.AUTHORIZED && cancellation) {
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
        PurchaseOrder order = transactions.execute(status -> {
            PurchaseOrder current = repository.lockById(orderId);
            if (terminal(current.state())) return current;
            if (current.state() == OrderState.RESERVED) {
                if (!clock.instant().isBefore(current.checkoutExpiresAt())) {
                    expire(current);
                    return repository.requireById(orderId);
                }
                releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
                return repository.requireById(orderId);
            }
            repository.updateState(orderId, OrderState.CANCEL_PENDING, current.paymentAttemptId(), clock.instant());
            return repository.requireById(orderId);
        });
        if (terminal(order.state())) return find(orderId);

        PaymentResponse existing = payments.find(orderId);
        if (existing == null) return cancelWithoutPayment(orderId);
        PaymentResponse payment = payments.reconcileForCancellation(orderId);
        if (payment.state() == PaymentState.AUTHORIZED
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidAuthorization(orderId);
        }
        return applyPayment(orderId, payment);
    }

    private OrderResponse cancelWithoutPayment(UUID orderId) {
        return transactions.execute(status -> {
            PurchaseOrder current = repository.lockById(orderId);
            if (terminal(current.state())) return toResponse(current);
            releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
            return toResponse(repository.requireById(orderId));
        });
    }

    @Override
    public OrderResponse find(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return transactions.execute(status -> toResponse(repository.requireById(orderId)));
    }

    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public int expireDueCheckouts() {
        Instant now = clock.instant();
        List<UUID> dueOrders = repository.findReservedOrdersExpiredBy(now);
        int expired = 0;
        for (UUID orderId : dueOrders) {
            Integer changed = transactions.execute(status -> {
                PurchaseOrder order = repository.lockById(orderId);
                if (order.state() != OrderState.RESERVED || clock.instant().isBefore(order.checkoutExpiresAt())) {
                    return 0;
                }
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
            buyerLockRepository.lockBuyer(order.campaignId() + ":" + order.buyerRef());
            admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                    CheckoutSlotReleaseReason.INITIAL_AUTHORIZE_SUBMITTED);
            OrderState next = switch (payment.state()) {
                case CAPTURED -> {
                    inventory.claim(orderId);
                    yield OrderState.PAID;
                }
                case DECLINED -> {
                    if (payment.authorizationActive()) yield OrderState.PAYMENT_VOID_PENDING;
                    inventory.release(orderId);
                    yield order.state() == OrderState.CANCEL_PENDING ? OrderState.CANCELLED : OrderState.FAILED;
                }
                case VOIDED -> {
                    inventory.release(orderId);
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
        admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                CheckoutSlotReleaseReason.CHECKOUT_WINDOW_EXPIRED);
        ReservationResponse reservation = inventory.findReservation(order.orderId());
        if (reservation != null && reservation.status() == ReservationStatus.RESERVED) {
            inventory.release(order.orderId());
        }
        repository.updateState(order.orderId(), OrderState.EXPIRED, order.paymentAttemptId(), clock.instant());
    }

    private void releaseWithoutPayment(PurchaseOrder order, OrderState state, CheckoutSlotReleaseReason reason) {
        admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(), reason);
        ReservationResponse reservation = inventory.findReservation(order.orderId());
        if (reservation != null && reservation.status() == ReservationStatus.RESERVED) {
            inventory.release(order.orderId());
        }
        repository.updateState(order.orderId(), state, order.paymentAttemptId(), clock.instant());
    }

    private OrderResponse toResponse(PurchaseOrder order) {
        ReservationResponse reservation = inventory.findReservation(order.orderId());
        PaymentResponse payment = payments.find(order.orderId());
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        UUID paymentAttemptId = order.paymentAttemptId() != null
                ? order.paymentAttemptId() : payment == null ? null : payment.attemptId();
        QueueTicketResponse ticket = admission.find(order.campaignId(), order.ticketId());
        return new OrderResponse(order.orderId(), order.campaignId(), order.ticketId(), paymentAttemptId,
                order.state(), order.checkoutExpiresAt(), reservation == null ? null : reservation.status(),
                paymentState, ticket.status());
    }
    private static OrderException invalidRequest() {
        return new OrderException(OrderException.Code.INVALID_REQUEST, "orderId is required");
    }

    private static boolean terminal(OrderState state) {
        return state == OrderState.PAID || state == OrderState.FAILED
                || state == OrderState.EXPIRED || state == OrderState.CANCELLED;
    }
    private <T> T withOrderLock(UUID orderId, Supplier<T> work) {
        synchronized (ORDER_LOCKS[Math.floorMod(orderId.hashCode(), ORDER_LOCKS.length)]) {
            return work.get();
        }
    }

    private static Object[] createOrderLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }
}
