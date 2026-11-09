package com.atlas.order.service.impl;

import com.atlas.order.dto.request.OrderShipmentRequest;
import com.atlas.order.dto.response.OrderShipmentResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderFulfillment;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.OrderShipment;
import com.atlas.order.persistence.entity.OrderShipmentItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.projection.ShipmentProgress;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderShipmentStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderShipmentRepository;
import com.atlas.order.service.OrderShipmentService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderShipmentServiceImpl implements OrderShipmentService {
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderShipmentRepository shipments;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRefundRepository refunds;
    private final Clock clock;

    public OrderShipmentServiceImpl(OrderRepository orders, OrderItemRepository orderItems,
                                    OrderShipmentRepository shipments, OrderFulfillmentRepository fulfillments,
                                    OrderRefundRepository refunds, Clock clock) {
        this.orders = Objects.requireNonNull(orders);
        this.orderItems = Objects.requireNonNull(orderItems);
        this.shipments = Objects.requireNonNull(shipments);
        this.fulfillments = Objects.requireNonNull(fulfillments);
        this.refunds = Objects.requireNonNull(refunds);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public OrderShipmentResponse create(OrderShipmentRequest request) {
        Normalized facts = normalize(request);
        PurchaseOrder order = lockOrder(facts.orderId());
        requirePaid(order);
        requireSeller(order, facts.sellerId());

        var replay = shipments.findByOrderOrderIdAndIdempotencyKey(facts.orderId(), facts.idempotencyKey());
        if (replay.isPresent()) {
            if (!sameFacts(replay.get(), facts)) {
                throw conflict("Idempotency key was used for different shipment facts");
            }
            return response(replay.get());
        }

        OrderFulfillment fulfillment = requireFulfillment(facts.orderId());
        if (fulfillment.status() != FulfillmentStatus.IN_PROGRESS
                && fulfillment.status() != FulfillmentStatus.PARTIALLY_SHIPPED) {
            throw conflict("Shipment requires fulfillment to be in progress");
        }

        List<OrderItem> orderLines = orderItems.findByOrderOrderIdOrderBySkuIdAsc(facts.orderId());
        Map<UUID, OrderItem> byId = new HashMap<>();
        for (OrderItem line : orderLines) {
            byId.put(line.orderItemId(), line);
        }
        for (UUID itemId : facts.quantities().keySet()) {
            OrderItem item = byId.get(itemId);
            if (item == null || !order.sellerId().equals(item.sellerId())) {
                throw invalid("Shipment item does not belong to seller order");
            }
        }

        Map<UUID, Integer> directRefunds = refunds.preShipmentRefundedQuantitiesByOrder(facts.orderId());
        Map<UUID, ShipmentTotals> totalsByItem = loadShipmentTotals(facts.orderId());
        long totalShippable = 0;
        for (OrderItem line : orderLines) {
            int refunded = directRefunds.getOrDefault(line.orderItemId(), 0);
            if (refunded < 0 || refunded > line.quantity()) {
                throw conflict("Refund quantity exceeds ordered quantity");
            }
            totalShippable = Math.addExact(totalShippable, line.quantity() - refunded);
            ShipmentTotals current = totalsByItem.getOrDefault(line.orderItemId(), new ShipmentTotals(0, 0));
            long afterShipment = Math.addExact(current.shippedQuantity(),
                    facts.quantities().getOrDefault(line.orderItemId(), 0));
            if (afterShipment + refunded > line.quantity()) {
                throw conflict("Shipment exceeds remaining shippable quantity");
            }
        }
        if (totalShippable == 0) {
            throw conflict("Order has no shippable quantity");
        }

        OrderShipment shipment = new OrderShipment(order, facts.idempotencyKey(), facts.carrierCode(),
                facts.trackingNumber(), clock.instant());
        facts.quantities().forEach((id, quantity) -> shipment.addItem(byId.get(id), quantity));
        OrderShipment saved = shipments.save(shipment);
        facts.quantities().forEach((id, quantity) -> addShipped(totalsByItem, id, quantity));
        refreshSummary(fulfillment, orderLines, directRefunds, totalsByItem);
        return response(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderShipmentResponse> findAll(String buyerRef, UUID orderId) {
        if (buyerRef == null || buyerRef.isBlank()) {
            throw invalid("Buyer reference is required");
        }
        PurchaseOrder order = orders.findById(orderId).orElseThrow(() -> notFound("Order not found"));
        requirePaid(order);
        if (!order.buyerRef().equals(buyerRef)) {
            throw conflict("Order belongs to a different buyer");
        }
        return shipments.findWithItemsByOrderId(orderId).stream()
                .map(OrderShipmentServiceImpl::response).toList();
    }

    @Override
    @Transactional
    public OrderShipmentResponse deliver(UUID sellerId, UUID orderId, UUID shipmentId) {
        PurchaseOrder order = lockOrder(orderId);
        requirePaid(order);
        requireSeller(order, sellerId);
        OrderShipment shipment = shipments.findById(shipmentId)
                .filter(candidate -> candidate.orderId().equals(orderId))
                .orElseThrow(() -> new OrderException(OrderException.Code.SHIPMENT_NOT_FOUND,
                        "Shipment not found"));
        if (shipment.status() == OrderShipmentStatus.DELIVERED) {
            return response(shipment);
        }
        if (shipment.status() != OrderShipmentStatus.SHIPPED) {
            throw conflict("Shipment is not in transit");
        }

        OrderFulfillment fulfillment = requireFulfillment(orderId);
        List<OrderItem> orderLines = orderItems.findByOrderOrderIdOrderBySkuIdAsc(orderId);
        Map<UUID, Integer> directRefunds = refunds.preShipmentRefundedQuantitiesByOrder(orderId);
        Map<UUID, ShipmentTotals> totalsByItem = loadShipmentTotals(orderId);
        shipment.deliver(clock.instant());
        OrderShipment saved = shipments.save(shipment);
        for (OrderShipmentItem item : shipment.items()) {
            addDelivered(totalsByItem, item.orderItemId(), item.quantity());
        }
        refreshSummary(fulfillment, orderLines, directRefunds, totalsByItem);
        return response(saved);
    }

    private void refreshSummary(OrderFulfillment fulfillment, List<OrderItem> lines,
            Map<UUID, Integer> directRefunds, Map<UUID, ShipmentTotals> totalsByItem) {
        long totalShippable = 0;
        long totalDispatched = 0;
        long totalDelivered = 0;
        for (OrderItem line : lines) {
            int remainingRefund = directRefunds.getOrDefault(line.orderItemId(), 0);
            ShipmentTotals totals = totalsByItem.getOrDefault(line.orderItemId(), new ShipmentTotals(0, 0));
            totalShippable = Math.addExact(totalShippable, line.quantity() - remainingRefund);
            totalDispatched = Math.addExact(totalDispatched, totals.shippedQuantity());
            totalDelivered = Math.addExact(totalDelivered, totals.deliveredQuantity());
        }
        if (totalShippable > 0) {
            fulfillment.refreshSummary(totalShippable, totalDispatched, totalDelivered, clock.instant());
            fulfillments.save(fulfillment);
        }
    }

    private Map<UUID, ShipmentTotals> loadShipmentTotals(UUID orderId) {
        Map<UUID, ShipmentTotals> totals = new HashMap<>();
        for (ShipmentProgress progress :
                shipments.findProgressByOrderId(orderId, OrderShipmentStatus.DELIVERED)) {
            totals.put(progress.getOrderItemId(),
                    new ShipmentTotals(progress.getShippedQuantity(), progress.getDeliveredQuantity()));
        }
        return totals;
    }

    private static void addShipped(Map<UUID, ShipmentTotals> totals, UUID orderItemId, int quantity) {
        ShipmentTotals current = totals.getOrDefault(orderItemId, new ShipmentTotals(0, 0));
        totals.put(orderItemId, new ShipmentTotals(
                Math.addExact(current.shippedQuantity(), quantity), current.deliveredQuantity()));
    }

    private static void addDelivered(Map<UUID, ShipmentTotals> totals, UUID orderItemId, int quantity) {
        ShipmentTotals current = totals.getOrDefault(orderItemId, new ShipmentTotals(0, 0));
        totals.put(orderItemId, new ShipmentTotals(
                current.shippedQuantity(), Math.addExact(current.deliveredQuantity(), quantity)));
    }

    private static Normalized normalize(OrderShipmentRequest request) {
        if (request == null || request.sellerId() == null || request.orderId() == null
                || request.idempotencyKey() == null || request.items() == null || request.items().isEmpty()) {
            throw invalid("Shipment request is incomplete");
        }
        String key = request.idempotencyKey().trim();
        String carrier = blankToNull(request.carrierCode());
        String tracking = blankToNull(request.trackingNumber());
        if (key.isEmpty() || key.length() > 128 || (carrier != null && carrier.length() > 64)
                || (tracking != null && tracking.length() > 128)
                || ((carrier == null) != (tracking == null))) {
            throw invalid("Invalid shipment facts");
        }
        TreeMap<UUID, Integer> quantities = new TreeMap<>();
        for (OrderShipmentRequest.Item item : request.items()) {
            if (item == null || item.orderItemId() == null || item.quantity() <= 0
                    || quantities.putIfAbsent(item.orderItemId(), item.quantity()) != null) {
                throw invalid("Shipment items must have unique order item IDs and positive quantities");
            }
        }
        return new Normalized(request.sellerId(), request.orderId(), key, carrier, tracking, quantities);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean sameFacts(OrderShipment shipment, Normalized facts) {
        if (!Objects.equals(shipment.carrierCode(), facts.carrierCode())
                || !Objects.equals(shipment.trackingNumber(), facts.trackingNumber())) return false;
        Map<UUID, Integer> existing = new TreeMap<>();
        shipment.items().forEach(item -> existing.put(item.orderItemId(), item.quantity()));
        return existing.equals(facts.quantities());
    }

    private static OrderShipmentResponse response(OrderShipment shipment) {
        List<OrderShipmentResponse.Item> items = new ArrayList<>();
        for (OrderShipmentItem item : shipment.items()) {
            items.add(new OrderShipmentResponse.Item(item.orderItemId(), item.quantity()));
        }
        return new OrderShipmentResponse(shipment.shipmentId(), shipment.orderId(), shipment.idempotencyKey(),
                shipment.status(), shipment.carrierCode(), shipment.trackingNumber(), shipment.shippedAt(),
                shipment.deliveredAt(), items);
    }

    private OrderFulfillment requireFulfillment(UUID orderId) {
        return fulfillments.findById(orderId).orElseThrow(() -> notFound("Fulfillment not found"));
    }
    private PurchaseOrder lockOrder(UUID orderId) {
        return orders.findWithLockByOrderId(orderId).orElseThrow(() -> notFound("Order not found"));
    }
    private static void requirePaid(PurchaseOrder order) {
        if (order.state() != OrderState.PAID) throw conflict("Fulfillment requires a paid order");
    }
    private static void requireSeller(PurchaseOrder order, UUID sellerId) {
        if (sellerId == null || !order.sellerId().equals(sellerId)) throw conflict("Seller does not own order");
    }
    private static OrderException invalid(String message) {
        return new OrderException(OrderException.Code.INVALID_REQUEST, message);
    }
    private static OrderException conflict(String message) {
        return new OrderException(OrderException.Code.CONFLICT, message);
    }
    private static OrderException notFound(String message) {
        return new OrderException(OrderException.Code.NOT_FOUND, message);
    }
    private record Normalized(UUID sellerId, UUID orderId, String idempotencyKey, String carrierCode,
                              String trackingNumber, TreeMap<UUID, Integer> quantities) { }
    private record ShipmentTotals(long shippedQuantity, long deliveredQuantity) {
    }

}
