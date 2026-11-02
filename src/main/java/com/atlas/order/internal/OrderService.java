package com.atlas.order.internal;

import com.atlas.admission.AdmissionApi;
import com.atlas.admission.CheckoutSlotReleaseReason;
import com.atlas.admission.QueueTicketView;
import com.atlas.admission.TicketStatus;
import com.atlas.inventory.InventoryApi;
import com.atlas.inventory.ReservationView;
import com.atlas.inventory.ReservationStatus;
import com.atlas.inventory.ReserveInventoryCommand;
import com.atlas.order.OrderApi;
import com.atlas.order.OrderConflictException;
import com.atlas.order.OrderState;
import com.atlas.order.OrderView;
import com.atlas.payment.PaymentApi;
import com.atlas.payment.PaymentState;
import com.atlas.payment.PaymentView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderService implements OrderApi {

    private static final Object[] ORDER_LOCKS = createOrderLocks();

    private final JdbcOrderRepository repository;
    private final TransactionTemplate transactions;
    private final AdmissionApi admission;
    private final InventoryApi inventory;
    private final PaymentApi payments;
    private final Clock clock;
    private final Duration checkoutWindow;
    private final Supplier<UUID> ids;

    @Autowired
    public OrderService(PlatformTransactionManager transactionManager, JdbcOrderRepository repository,
                        AdmissionApi admission, InventoryApi inventory, PaymentApi payments, Clock clock,
                        @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow) {
        this(repository, transactionManager, admission, inventory, payments, clock, checkoutWindow, UUID::randomUUID);
    }

    OrderService(JdbcOrderRepository repository, PlatformTransactionManager transactionManager,
                 AdmissionApi admission, InventoryApi inventory, PaymentApi payments, Clock clock,
                 Duration checkoutWindow, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
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
    public OrderView startCheckout(UUID campaignId, UUID ticketId) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(ticketId, "ticketId");
        return transactions.execute(status -> {
            OrderRecord existing = repository.findByTicketId(ticketId);
            if (existing != null) {
                if (!existing.campaignId().equals(campaignId)) {
                    throw new OrderConflictException("ticket already belongs to a different campaign");
                }
                return view(existing);
            }
            QueueTicketView ticket = admission.requireCheckoutSlot(campaignId, ticketId);
            ticket = admission.startCheckout(campaignId, ticketId);
            existing = repository.findByTicketId(ticketId);
            if (existing != null) return view(existing);

            UUID orderId = ids.get();
            Instant now = clock.instant();
            Instant expiresAt = now.plus(checkoutWindow);
            boolean reserved = inventory.tryReserve(new ReserveInventoryCommand(
                    orderId, campaignId, ticket.skuId(), 1)).isPresent();
            OrderState state = reserved ? OrderState.RESERVED : OrderState.FAILED;
            if (!reserved) {
                admission.releaseCheckoutSlot(campaignId, ticketId, CheckoutSlotReleaseReason.INVENTORY_UNAVAILABLE);
            }
            repository.insert(new OrderRecord(orderId, campaignId, ticketId, ticket.buyerRef(), ticket.skuId(),
                    state, expiresAt, null), now, now);
            return view(repository.findById(orderId));
        });
    }

    @Override
    public OrderView submitPayment(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> submitPaymentLocked(orderId));
    }

    private OrderView submitPaymentLocked(UUID orderId) {
        OrderRecord prepared = transactions.execute(status -> {
            OrderRecord order = repository.lockById(orderId);
            if (order.state() == OrderState.RESERVED && !clock.instant().isBefore(order.checkoutExpiresAt())) {
                expire(order);
                return repository.findById(orderId);
            }
            if (order.state() != OrderState.RESERVED && order.state() != OrderState.PAYMENT_SUBMITTED) {
                return order;
            }
            repository.lockBuyer(order);
            if (repository.hasOtherUnresolvedPayment(order)) {
                throw new OrderConflictException("another payment for this buyer and campaign is unresolved");
            }
            if (order.state() == OrderState.RESERVED) {
                repository.updateState(orderId, OrderState.PAYMENT_SUBMITTED, order.paymentAttemptId(), clock.instant());
            }
            return repository.findById(orderId);
        });
        if (prepared.state() != OrderState.PAYMENT_SUBMITTED) return find(orderId);

        PaymentView payment = payments.submitInitial(orderId);
        if (payment.state() == PaymentState.DECLINED && payment.authorizationActive()) {
            payment = payments.voidAuthorization(orderId);
        }
        return applyPayment(orderId, payment);
    }

    @Override
    public OrderView reconcilePayment(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> reconcilePaymentLocked(orderId));
    }

    private OrderView reconcilePaymentLocked(UUID orderId) {
        OrderRecord order = repository.findById(orderId);
        PaymentView existing = payments.find(orderId);
        if (existing == null && order.state() != OrderState.PAYMENT_SUBMITTED) return find(orderId);
        boolean cancellation = order.state() == OrderState.CANCEL_PENDING
                || order.state() == OrderState.PAYMENT_VOID_PENDING;
        PaymentView payment = existing == null ? payments.submitInitial(orderId)
                : cancellation ? payments.reconcileForCancellation(orderId) : payments.reconcile(orderId);
        if (payment.state() == PaymentState.DECLINED && payment.authorizationActive()) {
            payment = payments.voidAuthorization(orderId);
        } else if (payment.state() == PaymentState.AUTHORIZED && cancellation) {
            payment = payments.voidAuthorization(orderId);
        }
        return applyPayment(orderId, payment);
    }

    @Override
    public OrderView cancel(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> cancelLocked(orderId));
    }

    private OrderView cancelLocked(UUID orderId) {
        OrderRecord order = transactions.execute(status -> {
            OrderRecord current = repository.lockById(orderId);
            if (terminal(current.state())) return current;
            if (current.state() == OrderState.RESERVED) {
                if (!clock.instant().isBefore(current.checkoutExpiresAt())) {
                    expire(current);
                    return repository.findById(orderId);
                }
                releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
                return repository.findById(orderId);
            }
            repository.updateState(orderId, OrderState.CANCEL_PENDING, current.paymentAttemptId(), clock.instant());
            return repository.findById(orderId);
        });
        if (terminal(order.state())) return find(orderId);

        PaymentView existing = payments.find(orderId);
        if (existing == null) return cancelWithoutPayment(orderId);
        PaymentView payment = payments.reconcileForCancellation(orderId);
        if (payment.state() == PaymentState.AUTHORIZED
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidAuthorization(orderId);
        }
        return applyPayment(orderId, payment);
    }

    private OrderView cancelWithoutPayment(UUID orderId) {
        return transactions.execute(status -> {
            OrderRecord current = repository.lockById(orderId);
            if (terminal(current.state())) return view(current);
            releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
            return view(repository.findById(orderId));
        });
    }

    @Override
    public OrderView find(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return transactions.execute(status -> view(repository.findById(orderId)));
    }

    int expireDueCheckouts() {
        Instant now = clock.instant();
        List<UUID> dueOrders = repository.findReservedOrdersExpiredBy(now);
        int expired = 0;
        for (UUID orderId : dueOrders) {
            Integer changed = transactions.execute(status -> {
                OrderRecord order = repository.lockById(orderId);
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

    private OrderView applyPayment(UUID orderId, PaymentView payment) {
        return transactions.execute(status -> {
            OrderRecord order = repository.lockById(orderId);
            repository.lockBuyer(order);
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
            return view(repository.findById(orderId));
        });
    }

    private void expire(OrderRecord order) {
        admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                CheckoutSlotReleaseReason.CHECKOUT_WINDOW_EXPIRED);
        ReservationView reservation = inventory.findReservation(order.orderId());
        if (reservation != null && reservation.status() == ReservationStatus.RESERVED) {
            inventory.release(order.orderId());
        }
        repository.updateState(order.orderId(), OrderState.EXPIRED, order.paymentAttemptId(), clock.instant());
    }

    private void releaseWithoutPayment(OrderRecord order, OrderState state, CheckoutSlotReleaseReason reason) {
        admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(), reason);
        ReservationView reservation = inventory.findReservation(order.orderId());
        if (reservation != null && reservation.status() == ReservationStatus.RESERVED) {
            inventory.release(order.orderId());
        }
        repository.updateState(order.orderId(), state, order.paymentAttemptId(), clock.instant());
    }

    private OrderView view(OrderRecord order) {
        ReservationView reservation = inventory.findReservation(order.orderId());
        PaymentView payment = payments.find(order.orderId());
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        UUID paymentAttemptId = order.paymentAttemptId() != null
                ? order.paymentAttemptId() : payment == null ? null : payment.attemptId();
        QueueTicketView ticket = admission.find(order.campaignId(), order.ticketId());
        return new OrderView(order.orderId(), order.campaignId(), order.ticketId(), paymentAttemptId,
                order.state(), order.checkoutExpiresAt(), reservation == null ? null : reservation.status(),
                paymentState, ticket.status());
    }

    private static boolean terminal(OrderState state) {
        return state == OrderState.PAID || state == OrderState.FAILED
                || state == OrderState.EXPIRED || state == OrderState.CANCELLED;
    }
    private <T> T withOrderLock(UUID orderId, Supplier<T> operation) {
        synchronized (ORDER_LOCKS[Math.floorMod(orderId.hashCode(), ORDER_LOCKS.length)]) {
            return operation.get();
        }
    }

    private static Object[] createOrderLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }
}
