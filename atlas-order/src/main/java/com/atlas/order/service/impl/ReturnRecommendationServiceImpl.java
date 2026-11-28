package com.atlas.order.service.impl;

import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.ReturnResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.OrderReturnStatus;
import com.atlas.order.model.ReturnItemCondition;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.OrderReturn;
import com.atlas.order.persistence.entity.OrderReturnItem;
import com.atlas.order.persistence.entity.OrderShipment;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderReturnRepository;
import com.atlas.order.repository.OrderShipmentRepository;
import com.atlas.order.service.ReturnRecommendationRules;
import com.atlas.order.service.ReturnRecommendationService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 반품 권고의 사실을 읽어 {@link ReturnRecommendationRules}에 넘긴다. 잠금을 잡지 않는 읽기 전용 트랜잭션이라
 * 권고 뒤에 상태가 바뀔 수 있다. 그래도 돈이 틀어지지 않는 것은 판매자의 검수 호출이 주문 행 잠금 아래에서
 * 수량과 금액을 다시 계산하기 때문이다(권고는 그 검사를 대신하지 않는다).
 */
@Service
public class ReturnRecommendationServiceImpl implements ReturnRecommendationService {
    private final TransactionTemplate transactions;
    private final OrderReturnRepository returns;
    private final OrderItemRepository items;
    private final OrderRefundRepository refundRecords;
    private final OrderShipmentRepository shipments;
    private final ReturnRecommendationRules rules;
    private final Clock clock;

    public ReturnRecommendationServiceImpl(PlatformTransactionManager manager, OrderReturnRepository returns,
            OrderItemRepository items, OrderRefundRepository refundRecords, OrderShipmentRepository shipments,
            ReturnRecommendationRules rules, Clock clock) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(manager));
        template.setReadOnly(true);
        this.transactions = template;
        this.returns = returns; this.items = items; this.refundRecords = refundRecords;
        this.shipments = shipments; this.rules = rules; this.clock = clock;
    }

    @Override
    public ReturnResponse.Recommendation recommend(UUID sellerId, UUID orderId, UUID returnId,
            OrderRequest.ReturnRecommendation request) {
        if (sellerId == null || orderId == null || returnId == null || request == null || request.items() == null)
            throw invalid("Seller, order, return and item conditions are required");
        return Objects.requireNonNull(transactions.execute(tx -> {
            OrderReturn row = returns.findWithItemsByReturnId(returnId)
                    .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Return not found"));
            if (!row.orderId().equals(orderId) || !row.sellerId().equals(sellerId))
                throw conflict("Seller does not own return");
            // 검수와 같은 전제: 수령했고 검수 결과가 아직 없어야 판매자가 권고를 따를 수 있다.
            if (row.status() != OrderReturnStatus.RECEIVED
                    || row.items().stream().anyMatch(line -> line.inspectionResult() != null))
                throw conflict("Recommendation is available only for received returns without inspection results");
            Map<UUID, ReturnItemCondition> conditions = conditions(request, row);

            Map<UUID, OrderItem> orderItems = new HashMap<>();
            items.findByOrderOrderIdOrderBySkuIdAsc(orderId).forEach(item -> orderItems.put(item.orderItemId(), item));
            Map<UUID, Integer> refunded = refundRecords.refundedQuantitiesByOrder(orderId);
            Map<UUID, OrderShipment> shipmentsById = new HashMap<>();
            shipments.findWithItemsByOrderId(orderId).forEach(shipment -> shipmentsById.put(shipment.shipmentId(), shipment));

            List<ReturnRecommendationRules.Line> lines = new ArrayList<>();
            for (OrderReturnItem line : row.items()) {
                OrderItem item = orderItems.get(line.orderItemId());
                if (item == null) throw conflict("Return item is not part of the paid order");
                int refundedQuantity = refunded.getOrDefault(line.orderItemId(), 0);
                OrderShipment shipment = line.shipmentId() == null ? null : shipmentsById.get(line.shipmentId());
                lines.add(new ReturnRecommendationRules.Line(line.orderItemId(), line.quantity(), item.quantity(),
                        refundedQuantity, refundAmount(item, line.quantity(), refundedQuantity),
                        shipment == null ? null : shipment.deliveredAt(), conditions.get(line.orderItemId())));
            }
            ReturnRecommendationRules.Outcome outcome = rules.evaluate(
                    new ReturnRecommendationRules.Facts(row.reason(), row.requestedAt(), lines));
            return response(row, conditions, outcome);
        }));
    }

    /** 남은 수량을 넘으면 계산할 수 없으므로 0을 넘기고, 규칙이 그 줄을 이미 환불된 것으로 거절한다. */
    private static long refundAmount(OrderItem item, int quantity, int refundedQuantity) {
        if (quantity <= 0 || refundedQuantity < 0 || quantity > item.quantity() - refundedQuantity) return 0L;
        return item.refundAmountWon(quantity, refundedQuantity);
    }

    private static Map<UUID, ReturnItemCondition> conditions(OrderRequest.ReturnRecommendation request, OrderReturn row) {
        Map<UUID, ReturnItemCondition> byId = new HashMap<>();
        for (OrderRequest.ReturnRecommendation.Item line : request.items()) {
            if (line == null || line.orderItemId() == null || line.condition() == null
                    || byId.putIfAbsent(line.orderItemId(), line.condition()) != null)
                throw invalid("Item condition ids must be unique and valid");
        }
        if (byId.size() != row.items().size()
                || row.items().stream().anyMatch(line -> !byId.containsKey(line.orderItemId())))
            throw invalid("Each return line needs exactly one item condition");
        return byId;
    }

    private ReturnResponse.Recommendation response(OrderReturn row, Map<UUID, ReturnItemCondition> conditions,
            ReturnRecommendationRules.Outcome outcome) {
        Map<UUID, Integer> quantities = new HashMap<>();
        row.items().forEach(line -> quantities.put(line.orderItemId(), line.quantity()));
        List<ReturnResponse.Recommendation.Item> lines = outcome.lines().stream()
                .map(line -> new ReturnResponse.Recommendation.Item(line.orderItemId(),
                        quantities.get(line.orderItemId()), conditions.get(line.orderItemId()),
                        line.suggestedResult(), line.refundAmountWon(), line.returnWindowEndsAt(), line.reason()))
                .toList();
        return new ReturnResponse.Recommendation(row.returnId(), row.orderId(), outcome.decision(), outcome.reasons(),
                outcome.refundAmountWon(), rules.reviewAmountWon(), row.reason(), row.reasonNote(), clock.instant(),
                lines);
    }

    private static OrderException conflict(String message) {
        return new OrderException(OrderException.Code.CONFLICT, message);
    }

    private static OrderException invalid(String message) {
        return new OrderException(OrderException.Code.INVALID_REQUEST, message);
    }
}
