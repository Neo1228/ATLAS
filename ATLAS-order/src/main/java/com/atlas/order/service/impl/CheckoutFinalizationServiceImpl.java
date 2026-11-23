package com.atlas.order.service.impl;

import com.atlas.order.dto.response.CartItemResponse;
import com.atlas.order.dto.response.CartResponse;
import com.atlas.order.service.CartService;
import com.atlas.catalog.service.SellerCouponService;
import com.atlas.order.dto.response.LocationReservationResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.BuyerCheckoutRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CheckoutFinalizationService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.LocationReservationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SellerOrder별 workflow 예약이 staging된 뒤 Buyer checkout의 예약 intent를 확정한다.
 *
 * <p>원격 HTTP 호출은 DB transaction 안에서 실행하지 않는다. 저장된
 * {@code purchase_order.reservation_workflow_id} mapping이 모든 재시도의 기준이며,
 * 재시작한 process도 같은 workflow와 같은 원격 idempotency key를 재사용한다.
 */
@Service
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.enabled", havingValue = "true")
public class CheckoutFinalizationServiceImpl implements CheckoutFinalizationService {
    private final LocationReservationService coordinator;
    private final BuyerCheckoutRepository checkouts;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final CartService carts;
    private final SellerCouponService sellerCoupons;
    private final CheckoutPaymentService checkoutPayments;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CheckoutFinalizationServiceImpl(LocationReservationService coordinator,
            BuyerCheckoutRepository checkouts, OrderRepository orders, OrderItemRepository orderItems,
            CartService carts, SellerCouponService sellerCoupons, CheckoutPaymentService checkoutPayments,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.checkouts = Objects.requireNonNull(checkouts);
        this.orders = Objects.requireNonNull(orders);
        this.orderItems = Objects.requireNonNull(orderItems);
        this.carts = Objects.requireNonNull(carts);
        this.sellerCoupons = Objects.requireNonNull(sellerCoupons);
        this.checkoutPayments = Objects.requireNonNull(checkoutPayments);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public double oldestPendingAgeSeconds(BuyerCheckoutState state) {
        if (state != BuyerCheckoutState.RESERVATION_PENDING && state != BuyerCheckoutState.CANCEL_PENDING) {
            throw new IllegalArgumentException("Unsupported checkout recovery state");
        }
        Instant oldestPendingAt = state == BuyerCheckoutState.RESERVATION_PENDING
                ? checkouts.findOldestRemoteCreatedAtByState(state)
                : checkouts.findOldestRemoteCancellationRequestedAt();
        if (oldestPendingAt == null) return 0;
        return Math.max(0, Duration.between(oldestPendingAt, clock.instant()).toMillis() / 1000.0);
    }

    @Override
    public void finalizeCheckout(UUID checkoutId) {
        settle(checkoutId, coordinator::retry);
    }

    // 복구 poll은 workflow 상태만 읽는다. 원격 재시도 시점은 coordinator recovery의 backoff가 결정한다.
    private void settle(UUID checkoutId, Function<UUID, LocationReservationResponse> observe) {
        List<PurchaseOrder> childOrders = transactions.execute(status ->
                orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId));
        if (childOrders == null || childOrders.isEmpty()
                || childOrders.stream().anyMatch(order -> order.reservationWorkflowId() == null)) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Checkout has no persisted reservation workflow mapping");
        }

        Map<UUID, LocationReservationResponse> outcomes = new HashMap<>();
        for (PurchaseOrder order : childOrders) {
            outcomes.put(order.orderId(), observe.apply(order.reservationWorkflowId()));
        }
        boolean failed = outcomes.values().stream().anyMatch(response ->
                "FAILED".equals(response.status()) || "NEEDS_REVIEW".equals(response.status()));
        if (failed) {
            compensate(checkoutId, childOrders);
            return;
        }
        Instant observedAt = clock.instant();
        boolean deadlinePassed = childOrders.stream()
                .anyMatch(order -> !observedAt.isBefore(order.checkoutExpiresAt()))
                || outcomes.values().stream().flatMap(response -> response.locations().stream())
                .anyMatch(location -> location.expiresAt() != null && !observedAt.isBefore(location.expiresAt()));
        if (deadlinePassed) {
            compensate(checkoutId, childOrders);
            return;
        }
        boolean allReserved = outcomes.values().stream().allMatch(response ->
                "RESERVED".equals(response.status())
                        && !response.locations().isEmpty()
                        && response.locations().stream().allMatch(location ->
                        "RESERVED".equals(location.status()) && location.expiresAt() != null
                                && location.expiresAt().isAfter(clock.instant())));
        if (!allReserved) return;
        Instant remoteDeadline = outcomes.values().stream()
                .flatMap(response -> response.locations().stream())
                .map(location -> location.expiresAt())
                .min(Instant::compareTo).orElseThrow();

        transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(
                            OrderException.Code.NOT_FOUND, "Buyer checkout not found"));
            List<PurchaseOrder> current = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
            if (checkout.state() != BuyerCheckoutState.RESERVATION_PENDING
                    || current.isEmpty()
                    || current.stream().anyMatch(order -> order.state() != OrderState.RESERVATION_PENDING)) {
                return null;
            }
            Instant now = clock.instant();
            if (!now.isBefore(remoteDeadline)
                    || current.stream().anyMatch(order -> !now.isBefore(order.checkoutExpiresAt()))) return null;
            Instant checkoutDeadline = current.stream().map(PurchaseOrder::checkoutExpiresAt)
                    .min(Instant::compareTo).filter(deadline -> deadline.isBefore(remoteDeadline))
                    .orElse(remoteDeadline);
            current.forEach(order -> order.limitCheckoutExpiry(checkoutDeadline));
            current.forEach(order -> order.updateState(OrderState.RESERVED, order.paymentAttemptId(), now));
            checkout.update(BuyerCheckoutState.RESERVED, checkout.totalWon(), now);
            // 이 checkout에 staging된 snapshot과 정확히 일치할 때만 비우고, 이후의 장바구니 변경은 보존한다.
            CartResponse cart = carts.lockForCheckout(checkout.buyerRef());
            if (!cart.items().isEmpty() && cartMatchesOrderItemSnapshot(cart.items(), current)) {
                carts.clearForCheckout(checkout.buyerRef());
            }
            return null;
        });
    }

    private void compensate(UUID checkoutId, List<PurchaseOrder> childOrders) {
        boolean compensated = true;
        for (PurchaseOrder order : childOrders) {
            LocationReservationResponse response = coordinator.cancel(order.reservationWorkflowId());
            if (!"FAILED".equals(response.status()) && !"CANCELLED".equals(response.status())) {
                compensated = false;
            }
        }
        if (!compensated) return;
        transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(
                            OrderException.Code.NOT_FOUND, "Buyer checkout not found"));
            if (checkout.state() != BuyerCheckoutState.RESERVATION_PENDING) return null;
            Instant now = clock.instant();
            for (PurchaseOrder order : orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId)) {
                if (order.state() == OrderState.RESERVATION_PENDING) {
                    if (order.sellerCouponId() != null) {
                        sellerCoupons.releaseRedemption(order.sellerId(), order.sellerCouponId(), order.orderId(), now);
                    }
                    order.updateState(OrderState.FAILED, order.paymentAttemptId(), now);
                }
            }
            checkout.update(BuyerCheckoutState.FAILED, checkout.totalWon(), now);
            return null;
        });
    }

    @Override
    public int finalizeDue(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("Finalizer batch size must be positive");
        int finalized = 0;
        for (BuyerCheckout pending : checkouts.findByStateOrderByCreatedAtAsc(
                BuyerCheckoutState.RESERVATION_PENDING, Limit.of(batchSize))) {
            UUID checkoutId = pending.checkoutId();
            List<PurchaseOrder> children = transactions.execute(status ->
                    orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId));
            if (children == null || children.isEmpty()
                    || children.stream().anyMatch(order -> order.reservationWorkflowId() == null)) {
                continue;
            }
            settle(checkoutId, coordinator::find);
            finalized++;
        }
        for (BuyerCheckout pending : checkouts.findRemoteByState(
                BuyerCheckoutState.CANCEL_PENDING, Limit.of(batchSize))) {
            checkoutPayments.cancel(pending.checkoutId(), "reservation-recovery:" + pending.checkoutId());
            finalized++;
        }
        return finalized;
    }

    private boolean cartMatchesOrderItemSnapshot(List<CartItemResponse> cartItems,
                                                 List<PurchaseOrder> childOrders) {
        List<Fact> cartFacts = cartItems.stream()
                .map(item -> new Fact(item.sellerId(), item.skuId(), item.quantity(), item.priceWon()))
                .sorted(FACT_ORDER).toList();
        List<Fact> existingFacts = childOrders.isEmpty() ? new ArrayList<>()
                : orderItems.findAllByOrderIds(childOrders.stream().map(PurchaseOrder::orderId).toList())
                        .stream()
                        .map(item -> new Fact(item.sellerId(), item.skuId(), item.quantity(), item.unitPriceWon()))
                        .collect(Collectors.toCollection(ArrayList::new));
        existingFacts.sort(FACT_ORDER);
        return cartFacts.equals(existingFacts);
    }

    private static final Comparator<Fact> FACT_ORDER = Comparator
            .comparing(Fact::sellerId).thenComparing(Fact::skuId)
            .thenComparingInt(Fact::quantity).thenComparingLong(Fact::priceWon);

    private record Fact(UUID sellerId, String skuId, int quantity, long priceWon) { }
}
