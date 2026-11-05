package com.atlas.order.service.impl;

import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderRefundSummaryResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderRefundStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.persistence.projection.RefundRecord;
import com.atlas.order.persistence.projection.RefundSummary;
import com.atlas.order.service.OrderRefundService;
import com.atlas.settlement.dto.request.RefundPostingRequest;
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderRefundServiceImpl implements OrderRefundService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRefundRepository refunds;
    private final InventoryService inventory;
    private final SettlementService settlement;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OrderRefundServiceImpl(PlatformTransactionManager transactionManager, OrderRepository orders,
                                  OrderItemRepository items, OrderFulfillmentRepository fulfillments,
                                  OrderRefundRepository refunds, InventoryService inventory,
                                  SettlementService settlement, Clock clock) {
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.orders = Objects.requireNonNull(orders);
        this.items = Objects.requireNonNull(items);
        this.fulfillments = Objects.requireNonNull(fulfillments);
        this.refunds = Objects.requireNonNull(refunds);
        this.inventory = Objects.requireNonNull(inventory);
        this.settlement = Objects.requireNonNull(settlement);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public OrderRefundResponse refund(OrderRefundRequest request) {
        if (request == null) throw invalidRequest("Refund request is required");
        UUID sellerId = request.sellerId();
        UUID orderId = request.orderId();
        String idempotencyKey = request.idempotencyKey();
        validateRequest(sellerId, orderId, idempotencyKey);
        List<OrderRefundRequest.Item> requestItems = normalize(request);
        return Objects.requireNonNull(transactions.execute(transaction -> {
            refunds.lockIdempotencyKey(idempotencyKey);
            var existing = refunds.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                RefundRecord prior = existing.get();
                List<OrderRefundRequest.Item> priorItems = prior.items().stream()
                        .map(item -> new OrderRefundRequest.Item(item.orderItemId(), item.quantity()))
                        .sorted((left, right) -> left.orderItemId().compareTo(right.orderItemId()))
                        .toList();
                if (!prior.sellerId().equals(sellerId) || !prior.orderId().equals(orderId)
                        || !priorItems.equals(requestItems)) {
                    throw conflict("Idempotency key was used for different refund facts");
                }
                return response(prior);
            }

            PurchaseOrder order = lockOrder(orderId);
            if (!order.sellerId().equals(sellerId)) {
                throw conflict("Seller does not own order");
            }
            if (order.state() != OrderState.PAID) {
                throw conflict("Only paid orders can be refunded");
            }
            FulfillmentStatus fulfillmentStatus = fulfillments.findById(orderId)
                    .orElseThrow(() -> conflict("Paid order has no fulfillment state")).status();
            if (fulfillmentStatus != FulfillmentStatus.UNFULFILLED
                    && fulfillmentStatus != FulfillmentStatus.IN_PROGRESS) {
                throw conflict("Refunds are not allowed after shipment begins");
            }

            List<OrderItem> orderItems = items.findByOrderOrderIdOrderBySkuIdAsc(orderId);
            if (orderItems.isEmpty()) throw conflict("Paid order has no items");
            Map<UUID, OrderItem> orderItemsById = new HashMap<>();
            for (OrderItem item : orderItems) {
                if (!sellerId.equals(item.sellerId())) throw conflict("Order contains an item owned by another seller");
                orderItemsById.put(item.orderItemId(), item);
            }

            Map<UUID, Integer> alreadyRefunded = refunds.refundedQuantitiesByOrder(orderId);
            Map<UUID, Integer> requestedQuantities = new HashMap<>();
            long recordedAmountWon = 0;
            Map<String, Integer> quantitiesBySku = new TreeMap<>();
            try {
                for (OrderRefundRequest.Item line : requestItems) {
                    OrderItem item = orderItemsById.get(line.orderItemId());
                    if (item == null) throw invalidRequest("Refund item does not belong to order");
                    int previous = alreadyRefunded.getOrDefault(item.orderItemId(), 0);
                    if (line.quantity() > item.quantity() - previous) {
                        throw conflict("Refund quantity exceeds remaining ordered quantity");
                    }
                    requestedQuantities.put(item.orderItemId(), line.quantity());
                    recordedAmountWon = Math.addExact(recordedAmountWon, Math.multiplyExact(item.unitPriceWon(), line.quantity()));
                    quantitiesBySku.merge(item.skuId(), line.quantity(), Math::addExact);
                }
            } catch (ArithmeticException overflow) {
                throw invalidRequest("Refund amount or quantity is too large");
            }

            boolean fullyRefunded = orderItems.stream().allMatch(item ->
                    alreadyRefunded.getOrDefault(item.orderItemId(), 0)
                            + requestedQuantities.getOrDefault(item.orderItemId(), 0) == item.quantity());
            OrderRefundStatus resultStatus = fullyRefunded
                    ? OrderRefundStatus.FULLY_RECORDED : OrderRefundStatus.PARTIALLY_RECORDED;
            long previouslyRecordedAmountWon = refunds.findSummary(orderId)
                    .map(RefundSummary::recordedAmountWon).orElse(0L);
            long totalRecordedAmountWon;
            try {
                totalRecordedAmountWon = Math.addExact(previouslyRecordedAmountWon, recordedAmountWon);
            } catch (ArithmeticException overflow) {
                throw invalidRequest("Refund amount is too large");
            }

            var createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            UUID refundId = refunds.insertRequest(orderId, sellerId, idempotencyKey, recordedAmountWon,
                    resultStatus, createdAt);
            for (OrderRefundRequest.Item line : requestItems) {
                refunds.insertRequestItem(refundId, line.orderItemId(), line.quantity());
            }
            for (Map.Entry<String, Integer> quantity : quantitiesBySku.entrySet()) {
                inventory.restoreClaimed(orderId, sellerId, quantity.getKey(), quantity.getValue());
            }
            settlement.recordRefund(new RefundPostingRequest(orderId, refundId, sellerId, recordedAmountWon));
            refunds.upsertSummary(orderId, sellerId, totalRecordedAmountWon, resultStatus, createdAt);
            return new OrderRefundResponse(refundId, orderId, recordedAmountWon, resultStatus,
                    "LOCAL_LEDGER_ONLY", createdAt);
        }));
    }

    @Override
    public OrderRefundSummaryResponse find(UUID sellerId, UUID orderId) {
        if (sellerId == null || orderId == null) throw invalidRequest("sellerId and orderId are required");
        PurchaseOrder order = requireOrder(orderId);
        if (!order.sellerId().equals(sellerId)) throw conflict("Seller does not own order");
        return refunds.findSummary(orderId)
                .map(summary -> {
                    if (!summary.sellerId().equals(sellerId)) throw conflict("Refund summary seller does not match order");
                    return new OrderRefundSummaryResponse(orderId, summary.status(), summary.recordedAmountWon(),
                            "LOCAL_LEDGER_ONLY", summary.updatedAt());
                })
                .orElseGet(() -> new OrderRefundSummaryResponse(orderId, OrderRefundStatus.NONE, 0,
                        "LOCAL_LEDGER_ONLY", null));
    }

    private PurchaseOrder requireOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Order not found"));
    }

    private PurchaseOrder lockOrder(UUID orderId) {
        return orders.findWithLockByOrderId(orderId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Order not found"));
    }

    private static List<OrderRefundRequest.Item> normalize(OrderRefundRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw invalidRequest("At least one refund item is required");
        }
        List<OrderRefundRequest.Item> lines = new ArrayList<>(request.items().size());
        HashSet<UUID> itemIds = new HashSet<>();
        for (OrderRefundRequest.Item item : request.items()) {
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

    private static OrderRefundResponse response(RefundRecord refund) {
        return new OrderRefundResponse(refund.refundId(), refund.orderId(), refund.recordedAmountWon(),
                refund.status(), "LOCAL_LEDGER_ONLY", refund.createdAt());
    }

    private static OrderException conflict(String message) {
        return new OrderException(OrderException.Code.CONFLICT, message);
    }

    private static OrderException invalidRequest(String message) {
        return new OrderException(OrderException.Code.INVALID_REQUEST, message);
    }
}
