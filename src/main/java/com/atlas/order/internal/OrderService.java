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
import com.atlas.order.OrderNotFoundException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderService implements OrderApi {

    private record OrderRecord(UUID orderId, UUID campaignId, UUID ticketId, String buyerRef, String skuId,
                               OrderState state, Instant checkoutExpiresAt, UUID paymentAttemptId) { }

    private static final RowMapper<OrderRecord> ORDER_MAPPER = (rs, rowNum) -> new OrderRecord(
            rs.getObject("order_id", UUID.class), rs.getObject("campaign_id", UUID.class),
            rs.getObject("ticket_id", UUID.class), rs.getString("buyer_ref"), rs.getString("sku_id"),
            OrderState.valueOf(rs.getString("state")), rs.getTimestamp("checkout_expires_at").toInstant(),
            rs.getObject("payment_attempt_id", UUID.class));

    private static final String ORDER_COLUMNS = "order_id, campaign_id, ticket_id, buyer_ref, sku_id, state, checkout_expires_at, payment_attempt_id";
    private static final String UNRESOLVED_STATES = "'PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING', 'CANCEL_PENDING'";
    private static final Object[] ORDER_LOCKS = createOrderLocks();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AdmissionApi admission;
    private final InventoryApi inventory;
    private final PaymentApi payments;
    private final Clock clock;
    private final Duration checkoutWindow;
    private final Supplier<UUID> ids;

    @Autowired
    public OrderService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, AdmissionApi admission,
                        InventoryApi inventory, PaymentApi payments, Clock clock,
                        @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow) {
        this(jdbc, transactionManager, admission, inventory, payments, clock, checkoutWindow, UUID::randomUUID);
    }

    OrderService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, AdmissionApi admission,
                 InventoryApi inventory, PaymentApi payments, Clock clock, Duration checkoutWindow,
                 Supplier<UUID> ids) {
        this.jdbc = Objects.requireNonNull(jdbc);
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
            OrderRecord existing = findByTicket(ticketId);
            if (existing != null) {
                if (!existing.campaignId().equals(campaignId)) {
                    throw new OrderConflictException("ticket already belongs to a different campaign");
                }
                return view(existing);
            }
            QueueTicketView ticket = admission.requireCheckoutSlot(campaignId, ticketId);
            ticket = admission.startCheckout(campaignId, ticketId);
            existing = findByTicket(ticketId);
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
            jdbc.update("""
                    INSERT INTO purchase_order
                        (order_id, campaign_id, ticket_id, buyer_ref, sku_id, state,
                         checkout_expires_at, payment_attempt_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                    """, orderId, campaignId, ticketId, ticket.buyerRef(), ticket.skuId(), state.name(),
                    java.sql.Timestamp.from(expiresAt), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            return view(requireOrder(orderId));
        });
    }

    @Override
    public OrderView submitPayment(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> submitPaymentLocked(orderId));
    }

    private OrderView submitPaymentLocked(UUID orderId) {
        OrderRecord prepared = transactions.execute(status -> {
            OrderRecord order = lockOrder(orderId);
            if (order.state() == OrderState.RESERVED && !clock.instant().isBefore(order.checkoutExpiresAt())) {
                expire(order);
                return requireOrder(orderId);
            }
            if (order.state() != OrderState.RESERVED && order.state() != OrderState.PAYMENT_SUBMITTED) {
                return order;
            }
            lockBuyer(order);
            if (hasOtherUnresolvedPayment(order)) {
                throw new OrderConflictException("another payment for this buyer and campaign is unresolved");
            }
            if (order.state() == OrderState.RESERVED) {
                updateState(orderId, OrderState.PAYMENT_SUBMITTED, order.paymentAttemptId());
            }
            return requireOrder(orderId);
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
        OrderRecord order = findRecord(orderId);
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
            OrderRecord current = lockOrder(orderId);
            if (terminal(current.state())) return current;
            if (current.state() == OrderState.RESERVED) {
                if (!clock.instant().isBefore(current.checkoutExpiresAt())) {
                    expire(current);
                    return requireOrder(orderId);
                }
                releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
                return requireOrder(orderId);
            }
            updateState(orderId, OrderState.CANCEL_PENDING, current.paymentAttemptId());
            return requireOrder(orderId);
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
            OrderRecord current = lockOrder(orderId);
            if (terminal(current.state())) return view(current);
            releaseWithoutPayment(current, OrderState.CANCELLED, CheckoutSlotReleaseReason.BUYER_CANCELLED);
            return view(requireOrder(orderId));
        });
    }

    @Override
    public OrderView find(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return transactions.execute(status -> view(requireOrder(orderId)));
    }

    @Override
    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public int expireCheckoutWindows() {
        Instant now = clock.instant();
        List<UUID> dueOrders = jdbc.query("""
                SELECT order_id FROM purchase_order
                WHERE state = 'RESERVED' AND checkout_expires_at <= ?
                ORDER BY checkout_expires_at, order_id
                """, (rs, rowNum) -> rs.getObject("order_id", UUID.class), java.sql.Timestamp.from(now));
        int expired = 0;
        for (UUID orderId : dueOrders) {
            Integer changed = transactions.execute(status -> {
                OrderRecord order = lockOrder(orderId);
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
            OrderRecord order = lockOrder(orderId);
            lockBuyer(order);
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
            updateState(orderId, next, payment.attemptId());
            return view(requireOrder(orderId));
        });
    }

    private void expire(OrderRecord order) {
        admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(),
                CheckoutSlotReleaseReason.CHECKOUT_WINDOW_EXPIRED);
        ReservationView reservation = inventory.findReservation(order.orderId());
        if (reservation != null && reservation.status() == ReservationStatus.RESERVED) {
            inventory.release(order.orderId());
        }
        updateState(order.orderId(), OrderState.EXPIRED, order.paymentAttemptId());
    }

    private void releaseWithoutPayment(OrderRecord order, OrderState state, CheckoutSlotReleaseReason reason) {
        admission.releaseCheckoutSlot(order.campaignId(), order.ticketId(), reason);
        ReservationView reservation = inventory.findReservation(order.orderId());
        if (reservation != null && reservation.status() == ReservationStatus.RESERVED) {
            inventory.release(order.orderId());
        }
        updateState(order.orderId(), state, order.paymentAttemptId());
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

    private OrderRecord findRecord(UUID orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM purchase_order WHERE order_id = ?",
                ORDER_MAPPER, orderId).stream().findFirst().orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private OrderRecord requireOrder(UUID orderId) {
        return findRecord(orderId);
    }

    private OrderRecord lockOrder(UUID orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM purchase_order WHERE order_id = ? FOR UPDATE",
                ORDER_MAPPER, orderId).stream().findFirst().orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private OrderRecord findByTicket(UUID ticketId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM purchase_order WHERE ticket_id = ?",
                ORDER_MAPPER, ticketId).stream().findFirst().orElse(null);
    }

    private void updateState(UUID orderId, OrderState state, UUID paymentAttemptId) {
        java.sql.Timestamp updatedAt = java.sql.Timestamp.from(clock.instant());
        if (paymentAttemptId == null) {
            jdbc.update("UPDATE purchase_order SET state = ?, updated_at = ? WHERE order_id = ?",
                    state.name(), updatedAt, orderId);
        } else {
            jdbc.update("UPDATE purchase_order SET state = ?, payment_attempt_id = ?, updated_at = ? WHERE order_id = ?",
                    state.name(), paymentAttemptId, updatedAt, orderId);
        }
    }

    private void lockBuyer(OrderRecord order) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> { },
                order.campaignId() + ":" + order.buyerRef());
    }

    private boolean hasOtherUnresolvedPayment(OrderRecord order) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM purchase_order "
                        + "WHERE campaign_id = ? AND buyer_ref = ? AND order_id <> ? AND state IN ("
                        + UNRESOLVED_STATES + "))",
                Boolean.class, order.campaignId(), order.buyerRef(), order.orderId());
        return Boolean.TRUE.equals(exists);
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
