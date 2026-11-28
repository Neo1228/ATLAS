package com.atlas.order.service.impl;

import com.atlas.catalog.service.SellerAccountStanding;
import com.atlas.catalog.service.SellerCouponService;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.model.ReservationStatus;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.model.LocationReservationState;
import com.atlas.order.model.LocationReservationWorkflowState;
import com.atlas.order.model.OrderState;
import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.repository.BuyerCheckoutRepository;
import com.atlas.order.repository.ExpirySweepLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.ExpirySweepPolicy;
import com.atlas.order.service.LocationReservationService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.service.SettlementService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
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
    private final SellerCouponService sellerCoupons;
    private final SellerAccountStanding sellerStanding;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ExpirySweepPolicy expirySweep;
    private final ExpirySweepLockRepository expirySweepLock;
    private final Optional<LocationReservationService> locationReservations;



    public CheckoutPaymentServiceImpl(PlatformTransactionManager transactionManager,
            BuyerCheckoutRepository checkouts, OrderRepository orders, OrderItemRepository items,
            InventoryService inventory, OrderFulfillmentService fulfillment, PaymentService payments,
            SettlementService settlement, SellerCouponService sellerCoupons, SellerAccountStanding sellerStanding,
            Clock clock,
            @Value("${atlas.orders.expiry-sweep-batch-size:100}") int expirySweepBatchSize,
            @Value("${atlas.orders.expiry-sweep-time-budget:PT2S}") Duration expirySweepTimeBudget,
            ExpirySweepLockRepository expirySweepLock,
            Optional<LocationReservationService> locationReservations) {
        this.locationReservations = locationReservations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.checkouts = Objects.requireNonNull(checkouts);
        this.orders = Objects.requireNonNull(orders);
        this.items = Objects.requireNonNull(items);
        this.inventory = Objects.requireNonNull(inventory);
        this.fulfillment = Objects.requireNonNull(fulfillment);
        this.payments = Objects.requireNonNull(payments);
        this.settlement = Objects.requireNonNull(settlement);
        this.sellerCoupons = Objects.requireNonNull(sellerCoupons);
        this.sellerStanding = Objects.requireNonNull(sellerStanding);
        this.clock = Objects.requireNonNull(clock);
        this.expirySweep = new ExpirySweepPolicy(expirySweepBatchSize, expirySweepTimeBudget);
        this.expirySweepLock = expirySweepLock;
    }

    @Override
    public CheckoutResponse submit(UUID checkoutId) {
        if (checkoutId == null) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "checkoutId required");
        }
        // 원격 재고 checkout은 승인까지만 진행한 뒤 claim을 확인하고 매입한다. 매입 뒤 재고 부족이 드러나면
        // 실제 청구 후 환불이 필요해지므로, 자금 이동 전에 재고를 판매 확정 상태로 만든다.
        boolean remote = checkoutHasRemoteReservation(checkoutId);
        BuyerCheckout prepared = transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
            if (checkout.state() == BuyerCheckoutState.RESERVED) {
                List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
                if (children.isEmpty()) {
                    throw new OrderException(OrderException.Code.CONFLICT, "Checkout has no Seller orders");
                }
                // checkout을 만든 뒤 Seller가 정지·폐쇄됐으면 결제로 넘어가지 않는다. 상태를 바꾸지 않고 거절하므로
                // 예약은 checkout 만료 sweep이 풀어 준다.
                if (children.stream().map(PurchaseOrder::sellerId).distinct().anyMatch(sellerStanding::isDisabled)) {
                    throw new OrderException(OrderException.Code.CONFLICT, "Seller is not accepting orders");
                }
                Instant now = clock.instant();
                if (children.stream().anyMatch(order -> order.state() != OrderState.RESERVED)) {
                    throw new OrderException(OrderException.Code.CONFLICT, "Checkout children are not all reserved");
                }
                if (children.stream().anyMatch(order -> order.checkoutExpiresAt() == null
                        || !now.isBefore(order.checkoutExpiresAt()))) {
                    for (PurchaseOrder order : children) {
                        inventory.releaseAll(order.orderId());
                        releaseCouponReservation(order, now);
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
            if (checkout.state() == BuyerCheckoutState.PAYMENT_PENDING
                    || checkout.state() == BuyerCheckoutState.PAYMENT_UNKNOWN) {
                PaymentResponse attempt = payments.prepareCheckout(
                        checkoutId, BigDecimal.valueOf(checkout.totalWon()));
                for (PurchaseOrder order : orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId)) {
                    if (order.state() == OrderState.PAYMENT_SUBMITTED) {
                        order.updateState(OrderState.PAYMENT_SUBMITTED, attempt.attemptId(), clock.instant());
                    }
                }
            }
            return checkout;
        });
        if (prepared == null || (prepared.state() != BuyerCheckoutState.PAYMENT_PENDING
                && prepared.state() != BuyerCheckoutState.PAYMENT_UNKNOWN)) {
            throw new OrderException(OrderException.Code.CONFLICT, "Checkout is expired or terminal");
        }
        PaymentResponse payment;
        try {
            payment = remote
                    ? payments.submitCheckoutAuthorization(checkoutId, BigDecimal.valueOf(prepared.totalWon()))
                    : payments.submitCheckout(checkoutId, BigDecimal.valueOf(prepared.totalWon()));
        } catch (PaymentException exception) {
            if (exception.code() == PaymentException.Code.PAYMENT_PROVIDER_DISABLED) {
                PaymentResponse attempt = payments.findCheckout(checkoutId);
                if (attempt == null || attempt.state() == PaymentState.NOT_STARTED) {
                    transactions.execute(status -> {
                        BuyerCheckout current = checkouts.findWithLockByCheckoutId(checkoutId)
                                .orElseThrow(() -> new OrderException(
                                        OrderException.Code.NOT_FOUND, "Checkout not found"));
                        if (current.state() == BuyerCheckoutState.PAYMENT_PENDING
                                || current.state() == BuyerCheckoutState.PAYMENT_UNKNOWN) {
                            Instant now = clock.instant();
                            for (PurchaseOrder order : orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId)) {
                                if (order.state() == OrderState.PAYMENT_SUBMITTED) {
                                    order.updateState(OrderState.RESERVED, order.paymentAttemptId(), now);
                                }
                            }
                            current.update(BuyerCheckoutState.RESERVED, current.totalWon(), now);
                        }
                        return null;
                    });
                }
            }
            throw exception;
        }
        BuyerCheckout latest = transactions.execute(status -> checkouts.findById(checkoutId)
                .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found")));
        boolean cancellationRequested = (latest.state() == BuyerCheckoutState.CANCEL_PENDING || latest.state() == BuyerCheckoutState.CANCELLED);
        if ((cancellationRequested && payment.state() == PaymentState.AUTHORIZED)
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidCheckoutAuthorization(checkoutId, "submit-void:" + checkoutId);
        }
        if (remote) payment = claimBeforeCapture(checkoutId, payment, "submit:" + checkoutId, cancellationRequested);
        return applyPaymentResult(checkoutId, payment);
    }

    @Override
    public CheckoutResponse completeApproval(UUID checkoutId, String providerOrderId) {
        PaymentResponse payment = payments.completeCheckoutApproval(checkoutId, providerOrderId);
        if (payment.state() == PaymentState.AUTHORIZATION_PENDING
                || payment.state() == PaymentState.CAPTURE_PENDING
                || payment.state() == PaymentState.UNKNOWN
                || payment.state() == PaymentState.VOID_PENDING) {
            return applyPaymentResult(checkoutId, payment);
        }
        return reconcile(checkoutId, "paypal-approval:" + providerOrderId);
    }

    @Override
    public CheckoutResponse reconcile(UUID checkoutId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 221) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Idempotency-Key is required");
        }
        boolean remote = checkoutHasRemoteReservation(checkoutId);
        BuyerCheckout current = transactions.execute(status -> checkouts.findById(checkoutId)
                .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found")));
        boolean cancellationRequested = current.state() == BuyerCheckoutState.CANCEL_PENDING
                || current.state() == BuyerCheckoutState.CANCELLED;
        if (cancellationRequested && noProviderWork(payments.findCheckout(checkoutId))
                && payments.abandonUnstartedCheckout(checkoutId)) {
            // 취소가 요청됐고 아직 PSP 에 보낸 작업이 없다. 여기서 결제 계층의 reconcile 을 부르면
            // NOT_STARTED 승인 단계가 실행 가능하다고 판단되어(canExecutePhase) 구매자가 이미 취소한
            // checkout 에 새 PayPal 주문이 만들어진다. 시도를 먼저 종결(펜스)했으므로 provider 호출은
            // 더 일어나지 않고, 그때에만 예약을 풀어 닫는다. 펜스에 실패하면(이미 provider 단계)
            // 아래 일반 경로가 provider 결과로 수렴시킨다.
            return toResponse(closeCancellationWithoutProviderWork(checkoutId));
        }
        PaymentResponse payment = cancellationRequested
                ? payments.reconcileCheckoutForCancellation(checkoutId, idempotencyKey)
                : remote
                        ? payments.reconcileCheckoutAuthorization(checkoutId, idempotencyKey)
                        : payments.reconcileCheckout(checkoutId, idempotencyKey);
        if ((cancellationRequested && payment.state() == PaymentState.AUTHORIZED)
                || (payment.state() == PaymentState.DECLINED && payment.authorizationActive())) {
            payment = payments.voidCheckoutAuthorization(checkoutId, idempotencyKey + ":void");
        }
        if (remote) payment = claimBeforeCapture(checkoutId, payment, idempotencyKey, cancellationRequested);
        return applyPaymentResult(checkoutId, payment);
    }

    /**
     * 승인된 원격 재고 checkout을 claim → 매입 순서로 진행한다. 모든 workflow가 claim돼야 매입하고,
     * claim이 만료·취소로 확정 거절되면 매입 대신 승인을 void해 자금 이동 없이 실패로 끝낸다. claim 결과가
     * 불확실하면 승인 상태를 유지하고 payment recovery가 같은 reservation ID로 다시 claim한다.
     */
    private PaymentResponse claimBeforeCapture(UUID checkoutId, PaymentResponse payment, String idempotencyKey,
                                               boolean cancellationRequested) {
        if (cancellationRequested) return payment;
        if (payment.state() == PaymentState.CAPTURED) {
            // 매입이 먼저 확정된 경우(이전 버전의 흐름 등)도 claim을 보장해야 PAID로 확정할 수 있다.
            claimRemoteInventory(checkoutId);
            return payment;
        }
        if (payment.state() != PaymentState.AUTHORIZED) return payment;
        return switch (claimRemoteInventory(checkoutId)) {
            case CLAIMED -> payments.reconcileCheckout(checkoutId, idempotencyKey + ":capture");
            case REJECTED -> payments.voidCheckoutAuthorization(checkoutId, idempotencyKey + ":claim-rejected-void");
            case PENDING -> payment;
        };
    }

    private LocationReservationService.ClaimOutcome claimRemoteInventory(UUID checkoutId) {
        LocationReservationService coordinator = locationReservations.orElse(null);
        if (coordinator == null) return LocationReservationService.ClaimOutcome.PENDING;
        List<PurchaseOrder> children = transactions.execute(status ->
                orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId));
        boolean pending = false;
        for (PurchaseOrder order : children) {
            if (order.reservationWorkflowId() == null) continue;
            LocationReservationService.ClaimOutcome outcome = coordinator.claim(order.reservationWorkflowId());
            if (outcome == LocationReservationService.ClaimOutcome.REJECTED) return outcome;
            if (outcome == LocationReservationService.ClaimOutcome.PENDING) pending = true;
        }
        return pending ? LocationReservationService.ClaimOutcome.PENDING
                : LocationReservationService.ClaimOutcome.CLAIMED;
    }

    private boolean remoteInventoryClaimed(List<PurchaseOrder> children) {
        LocationReservationService coordinator = locationReservations.orElse(null);
        if (coordinator == null) return false;
        for (PurchaseOrder order : children) {
            if (order.reservationWorkflowId() == null) continue;
            if (coordinator.find(order.reservationWorkflowId()).status() != LocationReservationWorkflowState.CLAIMED) {
                return false;
            }
        }
        return true;
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
            if (checkout.state() == BuyerCheckoutState.RESERVED
                    || checkout.state() == BuyerCheckoutState.RESERVATION_PENDING) {
                List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
                if (children.stream().anyMatch(order -> order.reservationWorkflowId() != null)) {
                    for (PurchaseOrder order : children) {
                        order.updateState(OrderState.CANCEL_PENDING, order.paymentAttemptId(), clock.instant());
                    }
                    checkout.update(BuyerCheckoutState.CANCEL_PENDING, checkout.totalWon(), clock.instant());
                    return checkout;
                }
                for (PurchaseOrder order : children) {
                    inventory.releaseAll(order.orderId());
                    releaseCouponReservation(order, clock.instant());
                    order.updateState(OrderState.CANCELLED, order.paymentAttemptId(), clock.instant());
                }
                checkout.update(BuyerCheckoutState.CANCELLED, checkout.totalWon(), clock.instant());
                return checkout;
            }
            if (checkout.state() == BuyerCheckoutState.PAYMENT_PENDING
                    || checkout.state() == BuyerCheckoutState.PAYMENT_UNKNOWN
                    || checkout.state() == BuyerCheckoutState.CANCEL_PENDING) {
                // 같은 트랜잭션에서 checkout과 결제 시도 행을 잠가 취소 요청이
                // 확정된 뒤 새로운 캡처가 예약되지 않도록 한다.
                payments.prohibitNewCheckoutCapture(checkoutId);
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
        if (checkoutHasRemoteReservation(checkoutId)) {
            PaymentResponse currentPayment = payments.findCheckout(checkoutId);
            if (currentPayment == null || currentPayment.state() == PaymentState.NOT_STARTED) {
                return finishRemoteCancellation(
                        checkoutId, BuyerCheckoutState.CANCELLED, OrderState.CANCELLED);
            }
        }
        PaymentResponse currentPayment = payments.findCheckout(checkoutId);
        if (noProviderWork(currentPayment)) {
            // 결제 시도가 없거나 NOT_STARTED 다.
            //
            // 그대로 예약을 풀면 안 된다: submit 은 checkout 잠금을 놓은 뒤 provider 를 호출하므로,
            // 이 순간 NOT_STARTED 로 보여도 곧 승인·캡처될 수 있다. 캡처가 이기면 재고는 이미 풀린
            // 채 결제만 성사된다(CheckoutBehaviorTest#capturedPaymentWinsCancellationRaceAndIsRecorded).
            //
            // 그래서 결제 시도 행에 펜스를 건다. abandonUnstartedCheckout 은 시도 행을 잠그고
            // NOT_STARTED 일 때만 종결한다. submitCheckout 의 시작 가드와 reservePhase 가 같은 행을
            // 확인하므로, 펜스가 성공하면 provider 호출은 일어나지 않는다. 그때에만 예약을 풀고
            // CANCELLED 로 닫는다. 펜스가 실패하면 provider 단계가 이미 시작된 것이므로 예약을 유지하고
            // CANCEL_PENDING 으로 두어 provider 결과(void/캡처)로 수렴시킨다.
            if (payments.abandonUnstartedCheckout(checkoutId)) {
                return toResponse(closeCancellationWithoutProviderWork(checkoutId));
            }
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
    public CheckoutResponse cancelFromProvider(UUID checkoutId, String cancelToken) {
        if (checkoutId == null || cancelToken == null || cancelToken.isBlank()
                || !payments.matchesCheckoutCancelToken(checkoutId, cancelToken)) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "Checkout and matching provider cancellation state required");
        }
        return cancel(checkoutId, "paypal-cancel:" + checkoutId + ":" + cancelToken);
    }

    @Override
    public CheckoutResponse find(UUID checkoutId) {
        BuyerCheckout checkout = transactions.execute(status -> requireCheckout(checkoutId));
        return toResponse(checkout);
    }

    private CheckoutResponse applyPaymentResult(UUID checkoutId, PaymentResponse snapshot) {
        if (snapshot == null || !checkoutId.equals(snapshot.checkoutId())) {
            throw new OrderException(OrderException.Code.CONFLICT, "Payment result does not match checkout");
        }
        boolean[] cancellationRequestedForRemoteCleanup = {false};
        boolean[] remoteReservationForCleanup = {false};
        PaymentResponse[] applied = {snapshot};
        BuyerCheckout updated = transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
            // 결제 결과는 checkout 행을 잠근 뒤 다시 읽는다. 호출자가 들고 온 스냅숏은 잠금 밖에서 읽은 값이라,
            // 다른 인스턴스가 그 사이 캡처를 끝내고 PAID를 커밋했으면 오래된 값이다. 결제 소유자 잠금
            // (OwnerLockRegistry)은 JVM 안에서만 직렬화하므로 인스턴스가 둘 이상이면 이 재조회만이 PAID를
            // PAYMENT_PENDING으로 되돌리는 쓰기를 막는다(EXP-118 H2). 결제 상태는 앞으로만 가므로 잠금 아래에서
            // 읽은 값은 스냅숏보다 오래되지 않는다.
            PaymentResponse payment = Objects.requireNonNullElse(payments.findCheckout(checkoutId), snapshot);
            applied[0] = payment;
            List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
            if (children.isEmpty()) throw new OrderException(OrderException.Code.CONFLICT, "Checkout has no orders");
            boolean cancellationRequested = checkout.state() == BuyerCheckoutState.CANCEL_PENDING
                    || checkout.state() == BuyerCheckoutState.CANCELLED;
            cancellationRequestedForRemoteCleanup[0] = cancellationRequested;
            remoteReservationForCleanup[0] = children.stream().anyMatch(order -> order.reservationWorkflowId() != null);
            var now = clock.instant();
            if (payment.state() == PaymentState.CAPTURED) {
                List<SettlementPostingRequest.SellerOrderAllocation> allocations = new ArrayList<>();
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
                    allocations.add(new SettlementPostingRequest.SellerOrderAllocation(order.orderId(), order.sellerId(), amount));
                }
                if (total != checkout.totalWon()) throw new OrderException(OrderException.Code.CONFLICT, "Checkout item total mismatch");
                settlement.recordCheckoutCapture(
                        new SettlementPostingRequest.CheckoutSettlement(checkoutId, payment.attemptId(), total, allocations));
                boolean remoteCheckout = children.stream().anyMatch(order -> order.reservationWorkflowId() != null);
                if (remoteCheckout && !remoteInventoryClaimed(children)) {
                    // 매입은 확정됐지만 원격 claim이 기록되지 않았다. 판매 확정 전까지 PAID로 올리지 않는다.
                    for (PurchaseOrder order : children) {
                        order.updateState(OrderState.INVENTORY_CLAIM_PENDING, payment.attemptId(), now);
                    }
                    checkout.update(BuyerCheckoutState.INVENTORY_CLAIM_PENDING, checkout.totalWon(), now);
                } else {
                    for (PurchaseOrder order : children) {
                        if (order.state() != OrderState.PAID) {
                            // 원격 재고는 매입 전에 claim했으므로 로컬 재고를 다시 claim하지 않는다.
                            if (order.reservationWorkflowId() == null) inventory.claimAll(order.orderId());
                            redeemCouponReservation(order, now);
                            order.updateState(OrderState.PAID, payment.attemptId(), now);
                            fulfillment.initialize(order.orderId());
                        }
                    }
                    checkout.update(BuyerCheckoutState.PAID, checkout.totalWon(), now);
                }
            } else if (payment.state() == PaymentState.DECLINED || payment.state() == PaymentState.VOIDED) {
                if (payment.authorizationActive()) {
                    for (PurchaseOrder order : children) {
                        order.updateState(OrderState.PAYMENT_VOID_PENDING, payment.attemptId(), now);
                    }
                    checkout.update(cancellationRequested ? BuyerCheckoutState.CANCEL_PENDING
                            : BuyerCheckoutState.PAYMENT_PENDING, checkout.totalWon(), now);
                } else {
                    if (remoteReservationForCleanup[0]) {
                        for (PurchaseOrder order : children) {
                            order.updateState(OrderState.CANCEL_PENDING, payment.attemptId(), now);
                        }
                        checkout.update(BuyerCheckoutState.CANCEL_PENDING, checkout.totalWon(), now);
                    } else {
                        OrderState terminalOrderState = cancellationRequested
                                ? OrderState.CANCELLED : OrderState.FAILED;
                        BuyerCheckoutState terminalCheckoutState = cancellationRequested
                                ? BuyerCheckoutState.CANCELLED : BuyerCheckoutState.FAILED;
                        for (PurchaseOrder order : children) {
                            if (order.state() != OrderState.CANCELLED && order.state() != OrderState.FAILED) {
                                inventory.releaseAll(order.orderId());
                                releaseCouponReservation(order, now);
                                order.updateState(terminalOrderState, payment.attemptId(), now);
                            }
                        }
                        checkout.update(terminalCheckoutState, checkout.totalWon(), now);
                    }
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
            if (payment.attemptId() != null
                    && (checkout.state() == BuyerCheckoutState.PAID
                    || checkout.state() == BuyerCheckoutState.FAILED
                    || checkout.state() == BuyerCheckoutState.CANCELLED
                    || checkout.state() == BuyerCheckoutState.EXPIRED)
                    && !payment.authorizationActive()) {
                payments.markOwnerApplied(payment.attemptId());
            }
            return checkout;
        });
        PaymentResponse payment = applied[0];
        if (remoteReservationForCleanup[0]
                && (payment.state() == PaymentState.DECLINED || payment.state() == PaymentState.VOIDED)
                && !payment.authorizationActive() && updated.state() == BuyerCheckoutState.CANCEL_PENDING) {
            BuyerCheckoutState terminalCheckout = cancellationRequestedForRemoteCleanup[0]
                    ? BuyerCheckoutState.CANCELLED : BuyerCheckoutState.FAILED;
            OrderState terminalOrder = cancellationRequestedForRemoteCleanup[0]
                    ? OrderState.CANCELLED : OrderState.FAILED;
            return finishRemoteCancellation(checkoutId, terminalCheckout, terminalOrder);
        }
        return toResponse(updated);
    }

    @Override
    public int expireDueCheckouts() {
        // Same reasoning as expireDueOrders: one replica per tick, others skip.
        if (expirySweepLock == null) return sweepExpiredCheckouts();
        return expirySweepLock.runExclusive("checkouts", this::sweepExpiredCheckouts).orElse(0);
    }

    private int sweepExpiredCheckouts() {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + expirySweep.timeBudget().toNanos();
        List<UUID> due = orders.findExpiredCheckoutIds(
                OrderState.RESERVED, now, Limit.of(expirySweep.batchSize()));
        int expired = 0;
        for (UUID checkoutId : due) {
            if (System.nanoTime() >= deadline) break;
            Integer changed = transactions.execute(status -> {
                BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                        .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
                if (checkout.state() != BuyerCheckoutState.RESERVED) {
                    return 0;
                }
                Instant sweepNow = clock.instant();
                List<PurchaseOrder> children = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
                if (children.isEmpty() || children.stream().anyMatch(order -> order.state() != OrderState.RESERVED
                        || order.checkoutExpiresAt() == null || sweepNow.isBefore(order.checkoutExpiresAt()))) {
                    return 0;
                }
                if (children.stream().anyMatch(order -> order.reservationWorkflowId() != null)) {
                checkout.markExpiryCancellation();
                    for (PurchaseOrder order : children) {
                        order.updateState(OrderState.CANCEL_PENDING, order.paymentAttemptId(), sweepNow);
                    }
                    checkout.update(BuyerCheckoutState.CANCEL_PENDING, checkout.totalWon(), sweepNow);
                    return 2;
                }
                for (PurchaseOrder order : children) {
                    inventory.releaseAll(order.orderId());
                    releaseCouponReservation(order, clock.instant());
                    order.updateState(OrderState.EXPIRED, order.paymentAttemptId(), clock.instant());
                }
                checkout.update(BuyerCheckoutState.EXPIRED, checkout.totalWon(), clock.instant());
                return 1;
            });
            if (changed != null && changed == 2) {
                finishRemoteCancellation(checkoutId, BuyerCheckoutState.EXPIRED, OrderState.EXPIRED);
            }
            expired += changed == null ? 0 : (changed == 2 ? 1 : changed);
        }
        return expired;
    }

    private boolean checkoutHasRemoteReservation(UUID checkoutId) {
        List<PurchaseOrder> children = transactions.execute(status ->
                orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId));
        return children != null && children.stream().anyMatch(order -> order.reservationWorkflowId() != null);
    }
    private CheckoutResponse finishRemoteCancellation(UUID checkoutId,
                                                      BuyerCheckoutState terminalCheckout,
                                                      OrderState terminalOrder) {
        LocationReservationService coordinator = locationReservations.orElse(null);
        if (coordinator == null) return find(checkoutId);
        List<PurchaseOrder> children = transactions.execute(status ->
                orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId));
        if (children == null || children.isEmpty()
                || children.stream().anyMatch(order -> order.reservationWorkflowId() == null)) {
            return find(checkoutId);
        }
        boolean released = true;
        for (PurchaseOrder order : children) {
            var response = coordinator.cancel(order.reservationWorkflowId());
            boolean terminal = response.status() == LocationReservationWorkflowState.FAILED || response.status() == LocationReservationWorkflowState.CANCELLED;
            boolean allReleased = !response.locations().isEmpty()
                    && response.locations().stream().allMatch(location -> location.status() == LocationReservationState.RELEASED);
            if (!terminal && !allReleased) released = false;
        }
        if (!released) return find(checkoutId);
        transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(
                            OrderException.Code.NOT_FOUND, "Checkout not found"));
            if (checkout.state() != BuyerCheckoutState.CANCEL_PENDING) return null;
            boolean expired = checkout.cancellationTarget() == BuyerCheckoutState.EXPIRED;
            Instant now = clock.instant();
            List<PurchaseOrder> current = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
            for (PurchaseOrder order : current) {
                if (order.state() != OrderState.CANCELLED && order.state() != OrderState.FAILED
                        && order.state() != OrderState.EXPIRED) {
                    releaseCouponReservation(order, now);
                    order.updateState(expired ? OrderState.EXPIRED : terminalOrder, order.paymentAttemptId(), now);
                }
            }
            checkout.update(expired ? BuyerCheckoutState.EXPIRED : terminalCheckout, checkout.totalWon(), now);
            return null;
        });
        return find(checkoutId);
    }
    private void redeemCouponReservation(PurchaseOrder order, Instant at) {
        if (order.sellerCouponId() != null) {
            sellerCoupons.redeemRedemption(order.sellerId(), order.sellerCouponId(), order.orderId(), at);
        }
    }

    private static boolean noProviderWork(PaymentResponse payment) {
        return payment == null || payment.state() == PaymentState.NOT_STARTED;
    }

    /**
     * PSP 에 보낸 작업이 없는 취소를 닫는다. checkout 행을 잠그고 다시 확인한 뒤에만 해제한다.
     *
     * <p>이미 terminal 이면 아무것도 하지 않는다(재시도 멱등). 취소 요청 상태가 아니면 건드리지
     * 않는다 — 잠금을 기다리는 사이 다른 흐름이 상태를 바꿨을 수 있다.
     */
    private BuyerCheckout closeCancellationWithoutProviderWork(UUID checkoutId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            BuyerCheckout checkout = checkouts.findWithLockByCheckoutId(checkoutId)
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
            if (checkout.state() != BuyerCheckoutState.CANCEL_PENDING) return checkout;
            Instant now = clock.instant();
            for (PurchaseOrder order : orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId)) {
                if (order.state() != OrderState.CANCELLED && !order.state().isTerminal()) {
                    inventory.releaseAll(order.orderId());
                    releaseCouponReservation(order, now);
                    order.updateState(OrderState.CANCELLED, order.paymentAttemptId(), now);
                }
            }
            checkout.update(BuyerCheckoutState.CANCELLED, checkout.totalWon(), now);
            return checkout;
        }));
    }

    private void releaseCouponReservation(PurchaseOrder order, Instant at) {
        if (order.sellerCouponId() != null) {
            sellerCoupons.releaseRedemption(order.sellerId(), order.sellerCouponId(), order.orderId(), at);
        }
    }

    private BuyerCheckout requireCheckout(UUID checkoutId) {
        return checkouts.findById(checkoutId).orElseThrow(
                () -> new OrderException(OrderException.Code.NOT_FOUND, "Checkout not found"));
    }

    private CheckoutResponse toResponse(BuyerCheckout checkout) {
        PaymentResponse payment = payments.findCheckout(checkout.checkoutId());
        List<PurchaseOrder> childOrders = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkout.checkoutId());
        List<UUID> childOrderIds = childOrders.stream().map(PurchaseOrder::orderId).toList();
        Map<UUID, List<InventoryResponse.Reservation>> reservationsByOrder = childOrderIds.isEmpty()
                ? Map.of() : inventory.findReservationsByOrderIds(childOrderIds);
        Map<UUID, List<OrderItem>> itemsByOrder = childOrderIds.isEmpty()
                ? Map.of() : items.findAllByOrderIds(childOrderIds).stream()
                        .collect(Collectors.groupingBy(item -> item.order().orderId()));
        List<OrderViewResponse.Order> childResponses = childOrders.stream()
                .map(order -> toResponse(order, payment,
                        reservationsByOrder.getOrDefault(order.orderId(), List.of()),
                        itemsByOrder.getOrDefault(order.orderId(), List.of())))
                .toList();
        return new CheckoutResponse(checkout.checkoutId(), checkout.buyerRef(), checkout.state(),
                checkout.totalWon(), childResponses, payment == null ? null : payment.attemptId(),
                payment == null ? PaymentState.NOT_STARTED : payment.state(),
                payment == null ? null : payment.approvalUrl());
    }
    private OrderViewResponse.Order toResponse(PurchaseOrder order, PaymentResponse payment,
            List<InventoryResponse.Reservation> reservations, List<OrderItem> orderItems) {
        PaymentState paymentState = payment == null ? PaymentState.NOT_STARTED : payment.state();
        List<OrderViewResponse.Item> itemResponses = orderItems.stream()
                .map(item -> new OrderViewResponse.Item(item.orderItemId(), item.productId(), item.sellerId(),
                        item.skuId(), item.productName(), item.quantity(), item.unitPriceWon(),
                        item.couponDiscountWon(), item.lineTotalWon()))
                .toList();
        long total = 0;
        for (OrderItem item : orderItems) {
            total = Math.addExact(total, item.lineTotalWon());
        }
        ReservationStatus inventoryState = reservations.isEmpty() ? null : reservations.get(0).status();
        return new OrderViewResponse.Order(order.orderId(), order.campaignId(), order.ticketId(), order.paymentAttemptId(),
                order.state(), order.checkoutExpiresAt(), inventoryState, paymentState, null, order.source(),
                order.fulfillmentLocationId(), itemResponses, total, order.sellerCouponCode(),
                order.couponDiscountWon());
    }
}
