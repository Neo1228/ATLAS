package com.atlas.order.service.impl;

import com.atlas.order.dto.response.OrderFulfillmentResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderFulfillment;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.service.OrderFulfillmentService;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderFulfillmentServiceImpl implements OrderFulfillmentService {
    private final OrderRepository orders;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRefundRepository refunds;
    private final Clock clock;

    public OrderFulfillmentServiceImpl(OrderRepository orders, OrderFulfillmentRepository fulfillments,
                                       OrderRefundRepository refunds, Clock clock) {
        this.orders = Objects.requireNonNull(orders);
        this.fulfillments = Objects.requireNonNull(fulfillments);
        this.refunds = Objects.requireNonNull(refunds);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderFulfillmentResponse find(UUID orderId) {
        PurchaseOrder order = orders.requireById(orderId);
        requirePaid(order);
        return fulfillments.findById(orderId).map(OrderFulfillmentServiceImpl::response)
                .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Fulfillment not found"));
    }

    @Override
    @Transactional
    public OrderFulfillmentResponse updateStatus(UUID sellerId, UUID orderId, FulfillmentStatus status) {
        PurchaseOrder order = orders.lockById(orderId);
        requirePaid(order);
        if (!order.sellerId().equals(sellerId)) {
            throw new OrderException(OrderException.Code.CONFLICT, "Seller does not own order");
        }
        OrderFulfillment fulfillment = fulfillments.findById(orderId)
                .orElseThrow(() -> new OrderException(OrderException.Code.NOT_FOUND, "Fulfillment not found"));
        if (status != fulfillment.status() && refunds.isFullyRefunded(orderId)) {
            throw new OrderException(OrderException.Code.CONFLICT, "Fully refunded orders cannot advance fulfillment");
        }
        fulfillment.transitionTo(status, clock.instant());
        return response(fulfillments.save(fulfillment));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID orderId) {
        PurchaseOrder order = orders.lockById(orderId);
        requirePaid(order);
        if (fulfillments.findById(orderId).isEmpty()) {
            fulfillments.save(new OrderFulfillment(orderId, clock.instant()));
        }
    }

    private static void requirePaid(PurchaseOrder order) {
        if (order.state() != OrderState.PAID) {
            throw new OrderException(OrderException.Code.CONFLICT, "Fulfillment requires a paid order");
        }
    }

    private static OrderFulfillmentResponse response(OrderFulfillment fulfillment) {
        return new OrderFulfillmentResponse(fulfillment.orderId(), fulfillment.status(), fulfillment.createdAt(),
                fulfillment.updatedAt(), fulfillment.inProgressAt(), fulfillment.shippedAt(), fulfillment.deliveredAt());
    }
}
