package com.atlas.order.service.impl;

import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.order.dto.response.RefundResponse;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.OrderReturn;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.model.FulfillmentStatus;
import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderRefundStatus;
import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.OrderReturnStatus;
import com.atlas.order.model.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderReturnRepository;
import com.atlas.order.persistence.projection.RefundRecord;
import com.atlas.order.persistence.projection.RefundSummary;
import com.atlas.order.service.LocationRestockService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.model.PaymentRefundState;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.service.SettlementService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;


@Service
public class OrderRefundServiceImpl implements OrderRefundService {
    /** payment_refund_operation.idempotency_key 의 최대 길이. */
    private static final int MAX_PAYMENT_KEY_LENGTH = 128;

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRefundRepository refunds;
    private final InventoryService inventory;
    private final SettlementService settlement;
    private final PaymentService payments;
    private final OrderReturnRepository returns;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Optional<LocationRestockService> restockService;


    public OrderRefundServiceImpl(PlatformTransactionManager transactionManager, OrderRepository orders,
                                  OrderItemRepository items, OrderFulfillmentRepository fulfillments,
                                  OrderRefundRepository refunds, OrderReturnRepository returns, InventoryService inventory,
                                  SettlementService settlement, PaymentService payments, Clock clock,
            Optional<LocationRestockService> restockService) {
        this.restockService = restockService;
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.orders = Objects.requireNonNull(orders);
        this.items = Objects.requireNonNull(items);
        this.fulfillments = Objects.requireNonNull(fulfillments);
        this.refunds = Objects.requireNonNull(refunds);
        this.returns = Objects.requireNonNull(returns);
        this.inventory = Objects.requireNonNull(inventory);
        this.settlement = Objects.requireNonNull(settlement);
        this.payments = Objects.requireNonNull(payments);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RefundResponse.Refund refund(OrderRequest.Refund request) {
        return refund(request, null);
    }

    @Override
    public RefundResponse.Refund processInspectedReturnRefund(UUID returnId) {
        return refund(inspectedReturnRequest(returnId), returnId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundResponse.Refund prepareInspectedReturnRefund(UUID returnId) {
        OrderRequest.Refund request = inspectedReturnRequest(returnId);
        RefundRecord reserved = reserveRefund(request, returnId);
        OrderRefundPaymentStatus status = refunds.paymentStatus(reserved.refundId());
        return response(reserved, status, null);
    }

    private OrderRequest.Refund inspectedReturnRequest(UUID returnId) {
        if (returnId == null) throw invalidRequest("Return id required");
        OrderReturn row = returns.findWithItemsByReturnId(returnId)
                .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Return not found"));
        List<OrderRequest.Refund.Item> passed = row.items().stream()
                .filter(item -> item.inspectionResult() == OrderReturnInspectionResult.PASSED)
                .map(item -> new OrderRequest.Refund.Item(item.orderItemId(), item.quantity()))
                .sorted((left, right) -> left.orderItemId().compareTo(right.orderItemId())).toList();
        if (passed.isEmpty()) throw conflict("Return has no passed inspection lines");
        // 영구 실패한 이전 시도가 있으면 회차를 올려 새 송금 작업을 만든다.
        //
        // 파생 키 "return-" + returnId 는 한 번 FAILED 가 되면 재사용할 수 없다(payment 계층이
        // terminal FAILED 를 즉시 반환한다). "return-<id>-r<n>" 을 쓰면 새 operation 이 생겨
        // 실제로 다시 송금을 시도한다. 회차는 기존 키 개수에서 유도하므로 호출자가
        // key 를 고를 필요가 없다.
        String baseKey = "return-" + returnId;
        // 회차 구분자 "-r" 까지 포함해 세야 다른 반품 키와 섞이지 않는다. key 의 유일 범위가
        // 주문이므로 같은 주문 안에서만 센다. 이 수는 기본 키 뒤에 붙은 재시도 회차(-r1, -r2, ...)의 개수다.
        int retryAttempts = refunds.countByKeyPrefix(row.orderId(), baseKey + "-r");
        String latestKey = retryAttempts == 0 ? baseKey : baseKey + "-r" + retryAttempts;
        // 가장 최근 시도가 영구 실패(FAILED)일 때만 다음 회차를 만든다. 실패가 아닌 시도(진행 중, 결과 불명,
        // 응답 유실)는 같은 키를 다시 써서 같은 송금 작업을 이어 가야 이중 환불이 생기지 않는다.
        // 기본 키가 FAILED 인데 "-r" 개수가 0이라고 기본 키를 다시 쓰면 첫 재시도가 terminal FAILED 를
        // 곧바로 돌려받아 영원히 송금되지 않는다(EXP-093 G1 통합 시험이 찾은 결함).
        boolean latestFailed = refunds.findByIdempotencyKey(row.orderId(), latestKey)
                .map(record -> refunds.paymentStatus(record.refundId()) == OrderRefundPaymentStatus.FAILED)
                .orElse(false);
        String idempotencyKey = latestFailed ? baseKey + "-r" + (retryAttempts + 1) : latestKey;
        return new OrderRequest.Refund(row.sellerId(), row.orderId(), idempotencyKey, passed);
    }

    private RefundResponse.Refund refund(OrderRequest.Refund request, UUID returnId) {
        if (request == null) throw invalidRequest("Refund request is required");
        RefundRecord reserved = reserveRefund(request, returnId);
        OrderRefundPaymentStatus currentStatus = refunds.paymentStatus(reserved.refundId());
        if (currentStatus == OrderRefundPaymentStatus.NOT_APPLICABLE) {
            return response(reserved, currentStatus, null);
        }

        PurchaseOrder order = requireOrder(reserved.orderId());
        // 예약 시점에 고정한 payment key 를 그대로 쓴다. 다시 유도하면 다른 규칙으로
        // 만든 행이 새 송금 작업을 만들 수 있다.
        String paymentKey = refunds.paymentIdempotencyKey(reserved.refundId());
        PaymentRefundResponse payment;
        try {
            payment = order.checkoutId() == null
                    ? payments.refundOrder(order.orderId(), paymentKey, reserved.recordedAmountWon())
                    : payments.refundCheckout(order.checkoutId(), paymentKey, reserved.recordedAmountWon());
        } catch (PaymentException exception) {
            // 일시적 장애를 영구 실패로 만들지 않는다.
            //
            // 반품 환불의 idempotency key 는 "return-" + returnId 로 파생된다. 구매자·판매자가
            // key 를 고를 수 없으므로, 여기서 FAILED 를 기록하면 payment 계층의 terminal 처리
            // (executeRefundOperation 이 COMPLETED/FAILED 를 즉시 반환) 때문에 같은 반품은
            // 다시 송금을 시도하지 않는다. 즉 provider 가 잠깐 꺼져 있었던 것만으로 구매자가
            // 받아야 할 돈이 영구히 막힌다.
            //
            // 재시도 가능한 코드는 상태를 바꾸지 않고 그대로 올려, 같은 반품 검수를 다시
            // 호출하면 새 송금 시도가 나가게 한다.
            if (!isRetryableProviderFailure(exception)) {
                transactions.execute(status -> {
                    refunds.updatePaymentStatus(reserved.refundId(), OrderRefundPaymentStatus.FAILED);
                    settlement.releaseRefundReservation(new SettlementPostingRequest.Refund(
                            reserved.orderId(), reserved.refundId(), reserved.sellerId(),
                            reserved.recordedAmountWon()));
                    return null;
                });
            }
            throw exception;
        }
        return finalizeRefund(reserved, payment);
    }

    private RefundRecord reserveRefund(OrderRequest.Refund request, UUID returnId) {
        if (request == null) throw invalidRequest("Refund request is required");
        UUID sellerId = request.sellerId();
        UUID orderId = request.orderId();
        String idempotencyKey = request.idempotencyKey();
        validateRequest(sellerId, orderId, idempotencyKey);
        List<OrderRequest.Refund.Item> requestItems = normalize(request);
        return Objects.requireNonNull(transactions.execute(transaction -> {
            if (returnId != null) {
                PurchaseOrder returnOrder = lockOrder(orderId);
                if (returnOrder.reservationWorkflowId() != null && restockService.isEmpty()) {
                    throw conflict("Remote inventory restock is unavailable for this order");
                }
                if (!returnOrder.sellerId().equals(sellerId) || returnOrder.state() != OrderState.PAID)
                    throw conflict("Seller does not own paid order");
                FulfillmentStatus returnFulfillment = fulfillments.findById(orderId)
                        .orElseThrow(() -> conflict("Paid order has no fulfillment state")).status();
                OrderReturn persisted = returns.findWithLockByOrderIdAndReturnId(orderId, returnId)
                        .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Return not found"));
                if (persisted.status() != OrderReturnStatus.RECEIVED || returnFulfillment != FulfillmentStatus.DELIVERED
                        || !persisted.sellerId().equals(sellerId))
                    throw conflict("Return must be received for this delivered seller order");
                List<OrderRequest.Refund.Item> passedFacts = persisted.items().stream()
                        .filter(item -> item.inspectionResult() == OrderReturnInspectionResult.PASSED)
                        .map(item -> new OrderRequest.Refund.Item(item.orderItemId(), item.quantity()))
                        .sorted((left, right) -> left.orderItemId().compareTo(right.orderItemId())).toList();
                if (!passedFacts.equals(requestItems))
                    throw conflict("Refund lines must exactly match persisted passed inspection lines");
            }
            // key 의 유일 범위는 (주문, key) 다. 다른 주문·다른 판매자가 같은 key 를 써도 서로
            // 막지 않고, 같은 주문 안에서 같은 key 로 다른 사실을 보내면 아래에서 CONFLICT 다.
            refunds.lockIdempotencyKey(orderId, idempotencyKey);
            var existing = refunds.findByIdempotencyKey(orderId, idempotencyKey);
            if (existing.isPresent()) {
                RefundRecord prior = existing.get();
                List<OrderRequest.Refund.Item> priorItems = prior.items().stream()
                        .map(item -> new OrderRequest.Refund.Item(item.orderItemId(), item.quantity()))
                        .sorted((left, right) -> left.orderItemId().compareTo(right.orderItemId()))
                        .toList();
                if (!prior.sellerId().equals(sellerId) || !prior.orderId().equals(orderId)
                        || !priorItems.equals(requestItems)) {
                    throw conflict("Idempotency key was used for different refund facts");
                }
                return prior;
            }

            PurchaseOrder order = lockOrder(orderId);
            if (!order.sellerId().equals(sellerId)) {
                throw conflict("Seller does not own order");
            }
            if (order.reservationWorkflowId() != null && restockService.isEmpty()) {
                throw conflict("Remote inventory restock is unavailable for this order");
            }
            if (order.state() != OrderState.PAID) {
                throw conflict("Only paid orders can be refunded");
            }
            FulfillmentStatus fulfillmentStatus = fulfillments.findById(orderId)
                    .orElseThrow(() -> conflict("Paid order has no fulfillment state")).status();
            if (returnId == null) {
                if (fulfillmentStatus != FulfillmentStatus.UNFULFILLED
                        && fulfillmentStatus != FulfillmentStatus.IN_PROGRESS)
                    throw conflict("Refunds not allowed after shipment begins");
            } else {
                OrderReturn row = returns.findWithLockByOrderIdAndReturnId(orderId, returnId)
                        .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Return not found"));
                if (!row.sellerId().equals(sellerId) || row.status() != OrderReturnStatus.RECEIVED
                        || fulfillmentStatus != FulfillmentStatus.DELIVERED)
                    throw conflict("Return must be received for this delivered seller order");
                List<OrderRequest.Refund.Item> passed = row.items().stream()
                        .filter(item -> item.inspectionResult() == OrderReturnInspectionResult.PASSED)
                        .map(item -> new OrderRequest.Refund.Item(item.orderItemId(), item.quantity()))
                        .sorted((left, right) -> left.orderItemId().compareTo(right.orderItemId())).toList();
                if (!passed.equals(requestItems))
                    throw conflict("Refund lines must exactly match persisted passed inspection lines");
            }

            List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(orderId);
            if (orderItems.isEmpty()) throw conflict("Paid order has no items");
            Map<UUID, OrderItem> orderItemsById = new HashMap<>();
            for (OrderItem item : orderItems) {
                if (!sellerId.equals(item.sellerId())) throw conflict("Order contains an item owned by another seller");
                orderItemsById.put(item.orderItemId(), item);
            }

            Map<UUID, Integer> alreadyRefunded = refunds.refundedQuantitiesByOrder(orderId);
            Map<UUID, Integer> claimedByOpenReturns = new HashMap<>();
            Map<UUID, Integer> requestedQuantities = new HashMap<>();
            long recordedAmountWon = 0;

            try {
                if (returnId == null) {
                    for (OrderReturn returnRecord : returns.findAllByOrderId(orderId)) {
                        boolean openReturn = returnRecord.status() == OrderReturnStatus.REQUESTED
                                || returnRecord.status() == OrderReturnStatus.RECEIVED;
                        for (var line : returnRecord.items()) {
                            if (openReturn || line.inspectionResult() == OrderReturnInspectionResult.FAILED) {
                                claimedByOpenReturns.merge(line.orderItemId(), line.quantity(), Math::addExact);
                            }
                        }
                    }
                }
                for (OrderRequest.Refund.Item line : requestItems) {
                    OrderItem item = orderItemsById.get(line.orderItemId());
                    if (item == null) throw invalidRequest("Refund item does not belong to order");
                    int previouslyRefunded = alreadyRefunded.getOrDefault(item.orderItemId(), 0);
                    int claimedQuantity = claimedByOpenReturns.getOrDefault(item.orderItemId(), 0);
                    int previouslyClaimed = Math.addExact(previouslyRefunded, claimedQuantity);
                    if (previouslyClaimed > item.quantity()
                            || line.quantity() > item.quantity() - previouslyClaimed) {
                        throw conflict("Refund quantity exceeds remaining unclaimed ordered quantity");
                    }
                    requestedQuantities.put(item.orderItemId(), line.quantity());
                    recordedAmountWon = Math.addExact(recordedAmountWon,
                            item.refundAmountWon(line.quantity(), previouslyRefunded));

                }
            } catch (ArithmeticException overflow) {
                throw invalidRequest("Refund amount or quantity is too large");
            }
            boolean fullyRefunded = orderItems.stream().allMatch(item ->
                    alreadyRefunded.getOrDefault(item.orderItemId(), 0)
                            + requestedQuantities.getOrDefault(item.orderItemId(), 0) == item.quantity());
            OrderRefundStatus resultStatus = fullyRefunded
                    ? OrderRefundStatus.FULLY_RECORDED : OrderRefundStatus.PARTIALLY_RECORDED;
            var createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            String paymentKey = paymentIdempotencyKey(order, idempotencyKey);
            UUID refundId = refunds.insertRequest(orderId, sellerId, idempotencyKey, paymentKey,
                    recordedAmountWon, resultStatus, createdAt);
            for (OrderRequest.Refund.Item line : requestItems) {
                refunds.insertRequestItem(refundId, line.orderItemId(), line.quantity());
            }
            if (refunds.paymentStatus(refundId) != OrderRefundPaymentStatus.NOT_APPLICABLE) {
                PaymentRefundResponse payment = order.checkoutId() == null
                        ? payments.prepareRefundOrder(orderId, paymentKey, recordedAmountWon)
                        : payments.prepareRefundCheckout(
                                order.checkoutId(), paymentKey, recordedAmountWon);
                refunds.linkPaymentOperation(refundId, payment.refundOperationId());
                refunds.updatePaymentStatus(refundId, refundStatus(payment.status()));
                settlement.reserveRefund(new SettlementPostingRequest.Refund(
                        orderId, refundId, sellerId, recordedAmountWon));
            }
            return refunds.findByRefundId(refundId)
                    .orElseThrow(() -> new IllegalStateException("Refund request was not persisted"));
        }));
    }

    @Override
    public RefundResponse.Refund reconcilePayPalRefund(UUID paymentOperationId) {
        if (paymentOperationId == null) throw invalidRequest("Payment refund operation id is required");
        RefundRecord reserved = Objects.requireNonNull(transactions.execute(transaction ->
                refunds.findByPaymentOperationId(paymentOperationId)
                        .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND,
                                "Order refund request not found"))));
        return finalizeRefund(reserved, payments.findRefundOperation(paymentOperationId));
    }

    private RefundResponse.Refund finalizeRefund(RefundRecord reserved, PaymentRefundResponse payment) {
        if (payment == null || payment.refundOperationId() == null
                || payment.amountWon() != reserved.recordedAmountWon()
                || !"KRW".equals(payment.currency())) {
            throw conflict("PayPal refund facts do not match the order refund request");
        }
        OrderRefundPaymentStatus nextStatus = refundStatus(payment.status());
        RefundResponse.Refund applied = Objects.requireNonNull(transactions.execute(transaction -> {
            refunds.linkPaymentOperation(reserved.refundId(), payment.refundOperationId());
            boolean changed = refunds.updatePaymentStatus(reserved.refundId(), nextStatus);
            OrderRefundPaymentStatus currentStatus = refunds.paymentStatus(reserved.refundId());
            RefundRecord current = refunds.findByRefundId(reserved.refundId())
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND,
                            "Order refund request not found"));
            if (changed && currentStatus == OrderRefundPaymentStatus.COMPLETED) {
                applyConfirmedRefund(current);
            } else if (currentStatus == OrderRefundPaymentStatus.FAILED) {
                settlement.releaseRefundReservation(new SettlementPostingRequest.Refund(
                        current.orderId(), current.refundId(), current.sellerId(), current.recordedAmountWon()));
            }
            if ((currentStatus == OrderRefundPaymentStatus.COMPLETED
                    || currentStatus == OrderRefundPaymentStatus.FAILED)
                    && payment.refundOperationId() != null) {
                payments.markRefundApplied(payment.refundOperationId());
            }
            RefundRecord updated = refunds.findByRefundId(reserved.refundId()).orElse(current);
            return response(updated, currentStatus, payment.providerRefundId());
        }));
        if (restockService.isPresent()) restockService.get().dispatch(reserved.refundId());
        return applied;
    }

    private void applyConfirmedRefund(RefundRecord refund) {
        PurchaseOrder order = lockOrder(refund.orderId());
        Map<UUID, OrderItem> orderItemsById = new HashMap<>();
        for (OrderItem item : items.findByOrderOrderIdOrderBySkuIdAsc(refund.orderId())) {
            if (!refund.sellerId().equals(item.sellerId())) {
                throw conflict("Order contains an item owned by another seller");
            }
            orderItemsById.put(item.orderItemId(), item);
        }
        Map<String, Integer> quantitiesBySku = new TreeMap<>();
        for (var line : refund.items()) {
            OrderItem item = orderItemsById.get(line.orderItemId());
            if (item == null) throw conflict("Refund item does not belong to order");
            quantitiesBySku.merge(item.skuId(), line.quantity(), Math::addExact);
        }
        if (order.reservationWorkflowId() != null) {
            if (restockService.isEmpty()) throw conflict("Remote inventory restock is unavailable for this order");
            long returnItemCount = returns.countItemsByRefundId(refund.refundId());
            if (returnItemCount > 0) {
                List<OrderReturnRepository.ReturnRestockAllocation> sourceAllocations =
                        returns.findRestockAllocationsByRefundId(refund.refundId());
                if (sourceAllocations.size() > returnItemCount) {
                    throw conflict("Return shipment source count is inconsistent");
                }
                Map<UUID, Map<String, Integer>> quantitiesByLocation = new HashMap<>();
                Map<String, Integer> locatedBySku = new HashMap<>();
                sourceAllocations.forEach(source ->
                        locatedBySku.merge(source.getSkuId(), source.getQuantity(), Math::addExact));
                for (var source : sourceAllocations) {
                    quantitiesByLocation.computeIfAbsent(source.getFulfillmentLocationId(), ignored -> new HashMap<>())
                            .merge(source.getSkuId(), source.getQuantity(), Math::addExact);
                }
                Map<String, Integer> unlocated = new HashMap<>();
                quantitiesBySku.forEach((sku, quantity) -> {
                    int missing = quantity - locatedBySku.getOrDefault(sku, 0);
                    if (missing < 0) throw conflict("Return shipment restock quantity exceeds refund lines");
                    if (missing > 0) unlocated.put(sku, missing);
                });
                if (!unlocated.isEmpty()) quantitiesByLocation.put(null, unlocated);
                restockService.get().stageAtLocations(refund.refundId(), order.orderId(),
                        order.reservationWorkflowId(), refund.sellerId(), quantitiesByLocation);
            } else {
                restockService.get().stage(refund.refundId(), order.orderId(), order.reservationWorkflowId(),
                        refund.sellerId(), quantitiesBySku);
            }
        } else {
            for (Map.Entry<String, Integer> quantity : quantitiesBySku.entrySet()) {
                inventory.restoreClaimed(order.orderId(), refund.sellerId(), quantity.getKey(), quantity.getValue());
            }
        }
        settlement.recordRefund(new SettlementPostingRequest.Refund(
                order.orderId(), refund.refundId(), refund.sellerId(), refund.recordedAmountWon()));

        Map<UUID, Integer> completed = refunds.completedRefundedQuantitiesByOrder(order.orderId());
        boolean fullyRefunded = orderItemsById.values().stream()
                .allMatch(item -> completed.getOrDefault(item.orderItemId(), 0) >= item.quantity());
        OrderRefundStatus status = fullyRefunded
                ? OrderRefundStatus.FULLY_RECORDED : OrderRefundStatus.PARTIALLY_RECORDED;
        refunds.updateRefundStatus(refund.refundId(), status);

        long totalRecordedAmountWon;
        try {
            totalRecordedAmountWon = Math.addExact(refunds.findSummary(order.orderId())
                    .map(RefundSummary::recordedAmountWon).orElse(0L), refund.recordedAmountWon());
        } catch (ArithmeticException overflow) {
            throw invalidRequest("Refund amount is too large");
        }
        refunds.upsertSummary(order.orderId(), refund.sellerId(), totalRecordedAmountWon, status, clock.instant());
    }

    private static OrderRefundPaymentStatus refundStatus(PaymentRefundState state) {
        return switch (state) {
            case COMPLETED -> OrderRefundPaymentStatus.COMPLETED;
            case PENDING -> OrderRefundPaymentStatus.PENDING;
            case UNKNOWN -> OrderRefundPaymentStatus.UNKNOWN;
            case FAILED -> OrderRefundPaymentStatus.FAILED;
        };
    }

    @Override
    public RefundResponse.RefundSummary find(UUID sellerId, UUID orderId) {
        if (sellerId == null || orderId == null) throw invalidRequest("sellerId and orderId are required");
        PurchaseOrder order = requireOrder(orderId);
        if (!order.sellerId().equals(sellerId)) throw conflict("Seller does not own order");
        return refunds.findSummary(orderId)
                .map(summary -> {
                    if (!summary.sellerId().equals(sellerId)) throw conflict("Refund summary seller does not match order");
                    return new RefundResponse.RefundSummary(orderId, summary.status(), summary.recordedAmountWon(),
                            summary.updatedAt());
                })
                .orElseGet(() -> new RefundResponse.RefundSummary(orderId, OrderRefundStatus.NONE, 0, null));
    }

    private PurchaseOrder requireOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Order not found"));
    }

    private PurchaseOrder lockOrder(UUID orderId) {
        return orders.findWithLockByOrderId(orderId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Order not found"));
    }

    private static List<OrderRequest.Refund.Item> normalize(OrderRequest.Refund request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw invalidRequest("At least one refund item is required");
        }
        List<OrderRequest.Refund.Item> lines = new ArrayList<>(request.items().size());
        HashSet<UUID> itemIds = new HashSet<>();
        for (OrderRequest.Refund.Item item : request.items()) {
            if (item == null || item.orderItemId() == null || item.quantity() <= 0) {
                throw invalidRequest("Refund item id and positive quantity are required");
            }
            if (!itemIds.add(item.orderItemId())) throw invalidRequest("Duplicate order item ids must be combined");
            lines.add(item);
        }
        lines.sort((left, right) -> left.orderItemId().compareTo(right.orderItemId()));
        return List.copyOf(lines);
    }

    private static void validateRequest(UUID sellerId, UUID orderId, String idempotencyKey) {
        if (sellerId == null || orderId == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw invalidRequest("Seller, order, and an idempotency key of at most 128 characters are required");
        }
    }

    /**
     * payment 계층에 넘길 환불 key 를 정한다.
     *
     * <p>환불 key 의 유일 범위는 주문이다. 직접 결제 주문은 결제 attempt 가 그 주문 하나의 것이라
     * key 를 그대로 넘겨도 범위가 같다. checkout 주문은 여러 판매자 주문이 attempt 하나를
     * 공유하고 payment 는 (attempt, key) 로 송금 작업을 식별하므로, 주문 id 를 붙여야 다른
     * 판매자의 같은 key 와 합쳐지지 않는다. payment key 는 최대 128자라 넘치면 key 를 SHA-256
     * 으로 줄인다. 결과는 환불 요청 행에 저장해 재시도마다 같은 값을 쓴다.
     */
    private static String paymentIdempotencyKey(PurchaseOrder order, String idempotencyKey) {
        if (order.checkoutId() == null) return idempotencyKey;
        String scoped = "order:" + order.orderId() + ":" + idempotencyKey;
        if (scoped.length() <= MAX_PAYMENT_KEY_LENGTH) return scoped;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "order:" + order.orderId() + ":sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static RefundResponse.Refund response(RefundRecord refund, OrderRefundPaymentStatus paymentStatus,
                                                String providerRefundId) {
        return new RefundResponse.Refund(refund.refundId(), refund.orderId(), refund.recordedAmountWon(),
                refund.status(), paymentStatus, providerRefundId, refund.createdAt());
    }

    private static OrderException conflict(String message) {
        return new OrderException(OrderException.Code.CONFLICT, message);
    }

    private static OrderException invalidRequest(String message) {
        return new OrderException(OrderException.Code.INVALID_REQUEST, message);
    }

    /**
     * provider 가 일시적으로 응답하지 못한 경우인지 판단한다.
     *
     * <p>이 경우는 송금 실패가 아니라 "아직 결과를 모름"에 가깝다. 영구 실패로 기록하면
     * 파생 idempotency key 때문에 재시도가 불가능해진다.
     */
    private static boolean isRetryableProviderFailure(PaymentException exception) {
        return exception.code() == PaymentException.Code.PAYMENT_PROVIDER_DISABLED
                || exception.code() == PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE;
    }
}
