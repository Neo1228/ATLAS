package com.atlas.order.service.impl;

import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import com.atlas.order.dto.request.OrderReturnRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderReturnResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.OrderReturn;
import com.atlas.order.persistence.entity.OrderReturnItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderRefundPaymentStatus;
import com.atlas.order.persistence.state.OrderReturnInspectionResult;
import com.atlas.order.persistence.state.OrderReturnStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderReturnRepository;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderReturnService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderReturnServiceImpl implements OrderReturnService {
    private final TransactionTemplate transactions;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRefundRepository refundRecords;
    private final OrderReturnRepository returns;
    private final OrderRefundService refunds;
    private final Clock clock;

    public OrderReturnServiceImpl(PlatformTransactionManager manager, OrderRepository orders, OrderItemRepository items,
            OrderFulfillmentRepository fulfillments, OrderRefundRepository refundRecords,
            OrderReturnRepository returns, OrderRefundService refunds, Clock clock) {
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.orders = orders; this.items = items; this.fulfillments = fulfillments;
        this.refundRecords = refundRecords; this.returns = returns; this.refunds = refunds; this.clock = clock;
    }

    @Override
    public OrderReturnResponse request(OrderReturnRequest request) {
        if (request == null || request.buyerRef() == null || request.buyerRef().isBlank()
                || request.orderId() == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128 || request.items() == null || request.items().isEmpty()) {
            throw invalid("Buyer, order, key, and return lines are required");
        }
        List<OrderReturnRequest.Item> lines = normalize(request.items());
        return Objects.requireNonNull(transactions.execute(tx -> {
            PurchaseOrder order = lockOrder(request.orderId());
            if (!order.buyerRef().equals(request.buyerRef())) throw conflict("Buyer does not own order");
            var existing = returns.findByOrderIdAndIdempotencyKey(order.orderId(), request.idempotencyKey());
            if (existing.isPresent()) {
                OrderReturn prior = existing.get();
                if (!prior.buyerRef().equals(request.buyerRef()) || !prior.sellerId().equals(order.sellerId())
                        || !facts(prior).equals(lines)) throw conflict("Idempotency key was used for different return facts");
                return response(prior);
            }
            if (order.state() != OrderState.PAID) throw conflict("Only paid orders can be returned");
            if (fulfillments.findById(order.orderId()).map(f -> f.status() != FulfillmentStatus.DELIVERED).orElse(true))
                throw conflict("Returns are allowed only after delivery");
            Map<UUID, OrderItem> byId = new HashMap<>();
            List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(order.orderId());
            orderItems.forEach(item -> byId.put(item.orderItemId(), item));
            Map<UUID, Integer> refunded = refundRecords.refundedQuantitiesByOrder(order.orderId());
            Map<UUID, Integer> claimed = new HashMap<>();
            for (OrderReturn old : returns.findAllByOrderId(order.orderId())) {
                for (OrderReturnItem line : old.items()) {
                    if (old.status() == OrderReturnStatus.REQUESTED || old.status() == OrderReturnStatus.RECEIVED
                            || line.inspectionResult() == OrderReturnInspectionResult.FAILED)
                        claimed.merge(line.orderItemId(), line.quantity(), Math::addExact);
                }
            }
            for (OrderReturnRequest.Item line : lines) {
                OrderItem item = byId.get(line.orderItemId());
                if (item == null || !item.sellerId().equals(order.sellerId())) throw invalid("Return item does not belong to this seller order");
                int eligible = item.quantity() - refunded.getOrDefault(item.orderItemId(), 0) - claimed.getOrDefault(item.orderItemId(), 0);
                if (line.quantity() > eligible) throw conflict("Return quantity exceeds remaining eligible quantity");
            }
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            OrderReturn entity = new OrderReturn(order.orderId(), order.sellerId(), request.buyerRef(), request.idempotencyKey(), now);
            lines.forEach(line -> entity.addItem(new OrderReturnItem(line.orderItemId(), line.quantity())));
            return response(returns.saveAndFlush(entity));
        }));
    }

    @Override
    public OrderReturnResponse find(String buyerRef, UUID orderId, UUID returnId) {
        if (buyerRef == null || orderId == null || returnId == null) throw invalid("Buyer, order and return are required");
        OrderReturn row = returns.findWithItemsByReturnId(returnId).orElseThrow(() -> notFound("Return not found"));
        if (!row.orderId().equals(orderId) || !row.buyerRef().equals(buyerRef)) throw conflict("Buyer does not own return");
        return response(row);
    }

    @Override
    public OrderReturnResponse receive(UUID sellerId, UUID orderId, UUID returnId) {
        if (sellerId == null || orderId == null || returnId == null) throw invalid("Seller, order and return are required");
        return Objects.requireNonNull(transactions.execute(tx -> {
            lockOrder(orderId);
            OrderReturn row = lockedReturn(orderId, returnId);
            ownSeller(row, sellerId, orderId);
            if (row.status() == OrderReturnStatus.RECEIVED) return response(row);
            if (row.status() != OrderReturnStatus.REQUESTED) throw conflict("Return is no longer receivable");
            row.receive(clock.instant().truncatedTo(ChronoUnit.MICROS));
            return response(returns.saveAndFlush(row));
        }));
    }

    @Override
    public OrderReturnResponse inspect(UUID sellerId, UUID orderId, UUID returnId, OrderReturnInspectionRequest request) {
        if (sellerId == null || orderId == null || returnId == null || request == null || request.items() == null)
            throw invalid("반품 검수 결과가 필요합니다.");
        List<OrderReturnInspectionRequest.Item> decisions = new ArrayList<>(request.items());
        InspectionPreparation preparation = Objects.requireNonNull(transactions.execute(tx -> {
            lockOrder(orderId);
            OrderReturn row = lockedReturn(orderId, returnId);
            ownSeller(row, sellerId, orderId);
            Map<UUID, OrderReturnInspectionResult> byId = normalizeDecisions(decisions);
            if (row.status() != OrderReturnStatus.RECEIVED) {
                if (terminal(row.status()) && sameDecisions(row, byId)) {
                    return InspectionPreparation.completed(response(row));
                }
                throw conflict("반품이 수령 처리된 뒤에만 검수할 수 있으며, 검수가 끝난 반품은 다시 검수할 수 없습니다.");
            }
            if (byId.size() != row.items().size() || row.items().stream().anyMatch(
                    line -> !byId.containsKey(line.orderItemId()))) {
                throw invalid("요청한 각 반품 항목에 대해 검수 결과를 하나씩 입력해야 합니다.");
            }
            boolean previouslyInspected = row.items().stream()
                    .anyMatch(line -> line.inspectionResult() != null);
            if (previouslyInspected && !sameDecisions(row, byId)) {
                throw conflict("환불 준비 후에는 반품 검수 결과를 변경할 수 없습니다.");
            }
            Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
            boolean anyPassed = byId.values().stream()
                    .anyMatch(result -> result == OrderReturnInspectionResult.PASSED);
            if (!anyPassed) {
                row.items().forEach(line -> line.inspect(byId.get(line.orderItemId()), null, null, at));
                row.inspect(OrderReturnStatus.INSPECTION_FAILED, at);
                return InspectionPreparation.completed(response(returns.saveAndFlush(row)));
            }

            Map<UUID, Integer> previouslyRefunded = refundRecords.refundedQuantitiesByOrder(orderId);
            Map<UUID, OrderItem> orderItems = new HashMap<>();
            items.findByOrderOrderIdOrderBySkuIdAsc(orderId)
                    .forEach(item -> orderItems.put(item.orderItemId(), item));
            Map<UUID, Long> refundAmounts = new HashMap<>();
            long totalAmountWon = 0;
            try {
                for (OrderReturnItem line : row.items()) {
                    if (byId.get(line.orderItemId()) == OrderReturnInspectionResult.PASSED) {
                        OrderItem item = orderItems.get(line.orderItemId());
                        if (item == null) throw conflict("반품 항목이 결제된 주문에 포함되어 있지 않습니다.");
                        long amount = line.refundAmountWon() == null
                                ? item.refundAmountWon(line.quantity(),
                                        previouslyRefunded.getOrDefault(line.orderItemId(), 0))
                                : line.refundAmountWon();
                        refundAmounts.put(line.orderItemId(), amount);
                        totalAmountWon = Math.addExact(totalAmountWon, amount);
                        line.inspect(OrderReturnInspectionResult.PASSED, line.refundId(),
                                line.refundId() == null ? null : amount, at);
                    } else {
                        line.inspect(OrderReturnInspectionResult.FAILED, null, null, at);
                    }
                }
            } catch (ArithmeticException overflow) {
                throw invalid("환불 금액 또는 수량이 너무 큽니다.");
            }

            returns.saveAndFlush(row);
            OrderRefundResponse preparedRefund = refunds.prepareInspectedReturnRefund(returnId);
            if (preparedRefund.refundAmountWon() != totalAmountWon) {
                throw new IllegalStateException("검수 금액과 준비된 환불 금액이 일치하지 않습니다.");
            }
            for (OrderReturnItem line : row.items()) {
                if (byId.get(line.orderItemId()) == OrderReturnInspectionResult.PASSED) {
                    line.inspect(OrderReturnInspectionResult.PASSED,
                            preparedRefund.refundId(), refundAmounts.get(line.orderItemId()), at);
                }
            }
            returns.saveAndFlush(row);
            return InspectionPreparation.pending(preparedRefund.refundId(), at);
        }));
        if (preparation.completedResponse() != null) return preparation.completedResponse();

        OrderRefundResponse refund = refunds.processInspectedReturnRefund(returnId);
        if (!preparation.refundId().equals(refund.refundId())) {
            throw conflict("환불 처리 중 준비된 환불 식별자가 변경되었습니다.");
        }
        return Objects.requireNonNull(transactions.execute(tx -> {
            lockOrder(orderId);
            OrderReturn row = lockedReturn(orderId, returnId);
            ownSeller(row, sellerId, orderId);
            Map<UUID, OrderReturnInspectionResult> byId = normalizeDecisions(decisions);
            if (row.status() != OrderReturnStatus.RECEIVED) {
                if (terminal(row.status()) && sameDecisions(row, byId)) return response(row);
                throw conflict("환불 처리 중 반품 상태가 변경되었습니다.");
            }
            if (!sameDecisions(row, byId)) {
                throw conflict("환불 처리 중 반품 검수 결과가 변경되었습니다.");
            }
            OrderReturnStatus status = byId.values().stream()
                    .anyMatch(result -> result == OrderReturnInspectionResult.FAILED)
                    ? OrderReturnStatus.PARTIALLY_RECORDED : OrderReturnStatus.REFUND_RECORDED;
            row.inspect(status, preparation.inspectedAt());
            return response(returns.saveAndFlush(row));
        }));
    }

    private PurchaseOrder lockOrder(UUID orderId) {
        return orders.findWithLockByOrderId(orderId).orElseThrow(() -> notFound("Order not found"));
    }
    private OrderReturn lockedReturn(UUID orderId, UUID returnId) {
        return returns.findWithLockByOrderIdAndReturnId(orderId, returnId).orElseThrow(() -> notFound("Return not found"));
    }
    private static void ownSeller(OrderReturn row, UUID sellerId, UUID orderId) {
        if (!row.orderId().equals(orderId) || !row.sellerId().equals(sellerId)) throw conflict("Seller does not own return");
    }
    private static List<OrderReturnRequest.Item> normalize(List<OrderReturnRequest.Item> lines) {
        List<OrderReturnRequest.Item> result = new ArrayList<>();
        HashSet<UUID> ids = new HashSet<>();
        for (OrderReturnRequest.Item line : lines) {
            if (line == null || line.orderItemId() == null || line.quantity() <= 0) {
                throw invalid("Return line id and positive quantity are required");
            }
            if (!ids.add(line.orderItemId())) {
                throw invalid("Duplicate return lines must be combined");
            }
            result.add(line);
        }
        result.sort(Comparator.comparing(OrderReturnRequest.Item::orderItemId));
        return List.copyOf(result);
    }
    private static Map<UUID, OrderReturnInspectionResult> normalizeDecisions(List<OrderReturnInspectionRequest.Item> lines) {
        Map<UUID, OrderReturnInspectionResult> map = new HashMap<>();
        for (var line : lines) {
            if (line == null || line.orderItemId() == null || line.result() == null
                    || map.putIfAbsent(line.orderItemId(), OrderReturnInspectionResult.valueOf(line.result().name())) != null)
                throw invalid("Inspection result ids must be unique and valid");
        }
        return map;
    }
    private static List<OrderReturnRequest.Item> facts(OrderReturn row) {
        return row.items().stream().map(i -> new OrderReturnRequest.Item(i.orderItemId(), i.quantity()))
                .sorted(Comparator.comparing(OrderReturnRequest.Item::orderItemId)).toList();
    }
    private static boolean terminal(OrderReturnStatus status) {
        return status == OrderReturnStatus.REFUND_RECORDED || status == OrderReturnStatus.PARTIALLY_RECORDED
                || status == OrderReturnStatus.INSPECTION_FAILED;
    }
    private static boolean sameDecisions(OrderReturn row, Map<UUID, OrderReturnInspectionResult> input) {
        return input.size() == row.items().size() && row.items().stream().allMatch(i -> i.inspectionResult() != null
                && i.inspectionResult() == input.get(i.orderItemId()));
    }
    private OrderReturnResponse response(OrderReturn row) {
        OrderRefundPaymentStatus paymentStatus = row.items().stream().map(OrderReturnItem::refundId)
                .filter(Objects::nonNull).findFirst().map(refundRecords::paymentStatus)
                .orElse(OrderRefundPaymentStatus.NOT_APPLICABLE);
        return new OrderReturnResponse(row.returnId(), row.orderId(), row.sellerId(), row.buyerRef(), row.status(),
                paymentStatus, row.requestedAt(), row.receivedAt(), row.inspectedAt(), row.items().stream().map(i ->
                new OrderReturnResponse.Item(i.orderItemId(), i.quantity(), i.inspectionResult(), i.refundId(),
                        i.refundAmountWon(), i.inspectedAt())).toList());
    }
    private record InspectionPreparation(OrderReturnResponse completedResponse, UUID refundId, Instant inspectedAt) {
        private static InspectionPreparation completed(OrderReturnResponse response) {
            return new InspectionPreparation(response, null, null);
        }

        private static InspectionPreparation pending(UUID refundId, Instant inspectedAt) {
            return new InspectionPreparation(null, refundId, inspectedAt);
        }
    }

    private static OrderException notFound(String message) { return new OrderException(OrderException.Code.NOT_FOUND, message); }
    private static OrderException conflict(String message) { return new OrderException(OrderException.Code.CONFLICT, message); }
    private static OrderException invalid(String message) { return new OrderException(OrderException.Code.INVALID_REQUEST, message); }
}
