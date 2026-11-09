package com.atlas.order.service.impl;

import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderItemResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.BuyerCheckoutRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.dto.request.CheckoutSettlementPostingRequest;
import com.atlas.settlement.dto.request.SellerOrderAllocationRequest;
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CheckoutPaymentServiceImpl implements CheckoutPaymentService {
    private final BuyerCheckoutRepository checkouts;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final InventoryService inventory;
    private final OrderFulfillmentService fulfillment;
    private final PaymentService payments;
    private final SettlementService settlement;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CheckoutPaymentServiceImpl(PlatformTransactionManager transactionManager,
            BuyerCheckoutRepository checkouts, OrderRepository orders, OrderItemRepository items,
            InventoryService inventory, OrderFulfillmentService fulfillment, PaymentService payments,
            SettlementService settlement, Clock clock) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.checkouts = Objects.requireNonNull(checkouts);
        this.orders = Objects.requireNonNull(orders);
        this.items = Objects.requireNonNull(items);
        this.inventory = Objects.requireNonNull(inventory);
        this.fulfillment = Objects.requireNonNull(fulfillment);
        this.payments = Objects.requireNonNull(payments);
        this.settlement = Objects.requireNonNull(settlement);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public CheckoutResponse submit(UUID checkoutId) {
        if (checkoutId == null) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "checkoutId required");
        }
        BuyerCheckout prepared = transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
            if (checkout.state() == BuyerCheckoutState.RESERVED) {
                List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
                if (children.isEmpty()) {
                    throw new OrderException(OrderException.Code.CONFLICT, "Checkout has no Seller orders");
                }
                Instant now = clock.instant();
                if (children.stream().anyMatch(order -> order.state() != OrderState.RESERVED)) {
                    throw new OrderException(OrderException.Code.CONFLICT, "Checkout children are not all reserved");
                }
                if (children.stream().anyMatch(order -> order.checkoutExpiresAt() == null
                        || !now.isBefore(order.checkoutExpiresAt()))) {
                    for (PurchaseOrder order : children) {
                        inventory.releaseAll(order.orderId());
                        order.updateState(OrderState.EXPIRED, order.paymentAttemptId(), now);
                    }
                    checkout.update(BuyerCheckoutState.EXPIRED, checkout.totalWon(), now);
                    return checkout;
                }
                for (PurchaseOrder order : children) {
                    order.updateState(OrderState.PAYMENT_SUBMITTED, order.paymentAttemptId(), now);
                }
                checkout.update(BuyerCheckoutState.PAYMENT_PENDING, checkout.totalWon(), now);
            } else if (checkout.state() != BuyerCheckoutState.PAYMENT_PENDING
                    && checkout.state() != BuyerCheckoutState.PAYMENT_UNKNOWN) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Checkout is not eligible for payment submission");
            }
            return checkout;
        });
        if (prepared == null || (prepared.state() != BuyerCheckoutState.PAYMENT_PENDING
                && prepared.state() != BuyerCheckoutState.PAYMENT_UNKNOWN)) {
            throw new OrderException(OrderException.Code.CONFLICT, "Checkout is expired or terminal");
        }
        PaymentResponse payment = payments.submitCheckout(checkoutId);
        BuyerCheckout latest = transactions.execute(status -> checkouts.findById(checkoutId)
                .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found")));
        boolean cancellationRequested = (latest.state() == BuyerCheckoutState.CANCEL_PENDING || latest.state() == BuyerCheckoutState.CANCELLED);
        if ((cancellationRequested && payment.state() == PaymentState.AUTHORIZED)
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidCheckoutAuthorization(checkoutId, "submit-void:" + checkoutId);
        }
        return applyPaymentResult(checkoutId, payment);
    }

    @Override
    public CheckoutResponse reconcile(UUID checkoutId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 221) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Idempotency-Key is required");
        }
        BuyerCheckout current = transactions.execute(status -> checkouts.findById(checkoutId)
                .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found")));
        boolean cancellationRequested = current.state() == BuyerCheckoutState.CANCEL_PENDING
                || current.state() == BuyerCheckoutState.CANCELLED;
        PaymentResponse payment = payments.reconcileCheckout(checkoutId, idempotencyKey);
        if ((cancellationRequested && payment.state() == PaymentState.AUTHORIZED)
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidCheckoutAuthorization(checkoutId, idempotencyKey + ":void");
        }
        return applyPaymentResult(checkoutId, payment);
    }

    @Override
    public CheckoutResponse cancel(UUID checkoutId, String idempotencyKey) {
        if (checkoutId == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 221) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "Checkout and Idempotency-Key required");
        }
        BuyerCheckout marked = transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
            if (checkout.state() == BuyerCheckoutState.PAID) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Paid checkouts use the Seller order refund path");
            }
            if (checkout.state() == BuyerCheckoutState.RESERVED) {
                List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
                for (PurchaseOrder order : children) {
                    inventory.releaseAll(order.orderId());
                    order.updateState(OrderState.CANCELLED, order.paymentAttemptId(), clock.instant());
                }
                checkout.update(BuyerCheckoutState.CANCELLED, checkout.totalWon(), clock.instant());
                return checkout;
            }
            if (checkout.state() == BuyerCheckoutState.PAYMENT_PENDING
                    || checkout.state() == BuyerCheckoutState.PAYMENT_UNKNOWN
                    || checkout.state() == BuyerCheckoutState.CANCEL_PENDING) {
                List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
                for (PurchaseOrder order : children) {
                    if (!order.state().isTerminal()) {
                        order.updateState(OrderState.CANCEL_PENDING, order.paymentAttemptId(), clock.instant());
                    }
                }
                checkout.update(BuyerCheckoutState.CANCEL_PENDING, checkout.totalWon(), clock.instant());
            }
            return checkout;
        });
        if (marked.state() == BuyerCheckoutState.CANCELLED
                || marked.state() == BuyerCheckoutState.FAILED
                || marked.state() == BuyerCheckoutState.EXPIRED) {
            return toResponse(marked);
        }
        PaymentResponse currentPayment = payments.findCheckout(checkoutId);
        if (currentPayment == null || currentPayment.state() == PaymentState.NOT_STARTED) {
            // The submitter may have crossed the local pending transition but not yet persisted its attempt.
            // Keep every reservation held until that in-flight PSP operation becomes observable.
            return toResponse(marked);
        }
        PaymentResponse payment = payments.reconcileCheckoutForCancellation(checkoutId, idempotencyKey);
        if (payment.state() == PaymentState.AUTHORIZED
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidCheckoutAuthorization(checkoutId, idempotencyKey + ":void");
        }
        return applyPaymentResult(checkoutId, payment);
    }

    @Override
    public CheckoutResponse find(UUID checkoutId) {
        BuyerCheckout checkout = transactions.execute(status -> requireCheckout(checkoutId));
        return toResponse(checkout);
    }

    private CheckoutResponse applyPaymentResult(UUID checkoutId, PaymentResponse payment) {
        if (payment == null || !checkoutId.equals(payment.checkoutId())) {
            throw new OrderException(OrderException.Code.CONFLICT, "Payment result does not match checkout");
        }
        BuyerCheckout updated = transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
            List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
            if (children.isEmpty()) throw new OrderException(OrderException.Code.CONFLICT, "Checkout has no orders");
            boolean cancellationRequested = (checkout.state() == BuyerCheckoutState.CANCEL_PENDING || checkout.state() == BuyerCheckoutState.CANCELLED);
            var now = clock.instant();
            if (payment.state() == PaymentState.CAPTURED) {
                List<SellerOrderAllocationRequest> allocations = new ArrayList<>();
                long total = 0;
                for (PurchaseOrder order : children) {
                    List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId());
                    if (orderItems.isEmpty()) throw new OrderException(OrderException.Code.CONFLICT, "Order has no immutable items");
                    long amount = 0;
                    for (OrderItem item : orderItems) {
                        if (!order.sellerId().equals(item.sellerId())) throw new OrderException(OrderException.Code.CONFLICT, "Order item seller mismatch");
                        amount = Math.addExact(amount, item.lineTotalWon());
                    }
                    total = Math.addExact(total, amount);
                    allocations.add(new SellerOrderAllocationRequest(order.orderId(), order.sellerId(), amount));
                }
                if (total != checkout.totalWon()) throw new OrderException(OrderException.Code.CONFLICT, "Checkout item total mismatch");
                settlement.recordCheckoutCapture(
                        new CheckoutSettlementPostingRequest(checkoutId, payment.attemptId(), total, allocations));
                for (PurchaseOrder order : children) {
                    if (order.state() != OrderState.PAID) {
                        inventory.claimAll(order.orderId());
                        order.updateState(OrderState.PAID, payment.attemptId(), now);
                        fulfillment.initialize(order.orderId());
                    }
                }
                checkout.update(BuyerCheckoutState.PAID, checkout.totalWon(), now);
            } else if (payment.state() == PaymentState.DECLINED || payment.state() == PaymentState.VOIDED) {
                if (payment.authorizationActive()) {
                    for (PurchaseOrder order : children) {
                        order.updateState(OrderState.PAYMENT_VOID_PENDING, payment.attemptId(), now);
                    }
                    checkout.update(cancellationRequested ? BuyerCheckoutState.CANCEL_PENDING
                            : BuyerCheckoutState.PAYMENT_PENDING, checkout.totalWon(), now);
                } else {
                    OrderState terminalOrderState = cancellationRequested
                            ? OrderState.CANCELLED : OrderState.FAILED;
                    BuyerCheckoutState terminalCheckoutState = cancellationRequested
                            ? BuyerCheckoutState.CANCELLED : BuyerCheckoutState.FAILED;
                    for (PurchaseOrder order : children) {
                        if (order.state() != OrderState.CANCELLED && order.state() != OrderState.FAILED) {
                            inventory.releaseAll(order.orderId());
                            order.updateState(terminalOrderState, payment.attemptId(), now);
                        }
                    }
                    checkout.update(terminalCheckoutState, checkout.totalWon(), now);
                }
            } else {
                OrderState childState;
                BuyerCheckoutState checkoutState;
                if (cancellationRequested) {
                    childState = payment.state() == PaymentState.VOID_PENDING
                            ? OrderState.PAYMENT_VOID_PENDING : OrderState.CANCEL_PENDING;
                    checkoutState = BuyerCheckoutState.CANCEL_PENDING;
                } else {
                    childState = switch (payment.state()) {
                        case UNKNOWN -> OrderState.PAYMENT_UNKNOWN;
                        case VOID_PENDING -> OrderState.PAYMENT_VOID_PENDING;
                        default -> OrderState.PAYMENT_PENDING;
                    };
                    checkoutState = payment.state() == PaymentState.UNKNOWN
                            ? BuyerCheckoutState.PAYMENT_UNKNOWN : BuyerCheckoutState.PAYMENT_PENDING;
                }
                for (PurchaseOrder order : children) order.updateState(childState, payment.attemptId(), now);
                checkout.update(checkoutState, checkout.totalWon(), now);
            }
            return checkout;
        });
        return toResponse(updated);
    }

    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public int expireDueCheckouts() {
        List<UUID> due = orders.findExpiredCheckoutIds(OrderState.RESERVED, clock.instant());
        int expired = 0;
        for (UUID checkoutId : due) {
            Integer changed = transactions.execute(status -> {
                BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                        .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
                if (checkout.state() != BuyerCheckoutState.RESERVED) {
                    return 0;
                }
                List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
                if (children.isEmpty() || children.stream().anyMatch(order -> order.state() != OrderState.RESERVED
                        || order.checkoutExpiresAt() == null || clock.instant().isBefore(order.checkoutExpiresAt()))) {
                    return 0;
                }
                for (PurchaseOrder order : children) {
                    inventory.releaseAll(order.orderId());
                    order.updateState(OrderState.EXPIRED, order.paymentAttemptId(), clock.instant());
                }
                checkout.update(BuyerCheckoutState.EXPIRED, checkout.totalWon(), clock.instant());
                return 1;
            });
            expired += changed == null ? 0 : changed;
        }
        return expired;
    }

    private BuyerCheckout requireCheckout(UUID checkoutId) {
        return checkouts.findById(checkoutId).orElseThrow(
                () -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
    }

    private CheckoutResponse toResponse(BuyerCheckout checkout) {
        PaymentResponse payment = payments.findCheckout(checkout.checkoutId());
        List<OrderResponse> childResponses = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkout.checkoutId())
                .stream().map(order -> toResponse(order, payment)).toList();
        return new CheckoutResponse(checkout.checkoutId(), checkout.buyerRef(), checkout.state(),
                checkout.totalWon(), childResponses, payment == null ? null : payment.attemptId(),
                payment == null ? PaymentState.NOT_STARTED : payment.state());
    }
    private OrderResponse toResponse(PurchaseOrder order, PaymentResponse payment) {
        List<ReservationResponse> reservations = inventory.findReservations(order.orderId());
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId());
        List<OrderItemResponse> itemResponses = orderItems.stream()
                .map(item -> new OrderItemResponse(item.productId(), item.sellerId(), item.skuId(),
                        item.productName(), item.quantity(), item.unitPriceWon(),
                        item.couponDiscountWon(), item.lineTotalWon()))
                .toList();
        long total = 0;
        for (OrderItem item : orderItems) {
            total = Math.addExact(total, item.lineTotalWon());
        }
        ReservationStatus inventoryState = reservations.isEmpty() ? null : reservations.get(0).status();
        return new OrderResponse(order.orderId(), order.campaignId(), order.ticketId(), order.paymentAttemptId(),
                order.state(), order.checkoutExpiresAt(), inventoryState, paymentState, null, order.source(),
                order.fulfillmentLocationId(), itemResponses, total, order.sellerCouponCode(),
                order.couponDiscountWon());
    }
}
