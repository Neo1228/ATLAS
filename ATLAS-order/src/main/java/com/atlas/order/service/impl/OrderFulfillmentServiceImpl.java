package com.atlas.order.service.impl;

import com.atlas.order.dto.response.OrderFulfillmentResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderFulfillment;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
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
        PurchaseOrder order = orders.findById(orderId).orElseThrow(() -> notFound("Order not found"));
        requirePaid(order);
        return fulfillments.findById(orderId).map(OrderFulfillmentServiceImpl::response)
                .orElseThrow(() -> notFound("Fulfillment not found"));
    }

    @Override
    @Transactional
    public OrderFulfillmentResponse start(UUID sellerId, UUID orderId) {
        PurchaseOrder order = lockOrder(orderId);
        requirePaid(order);
        requireSeller(order, sellerId);
        OrderFulfillment fulfillment = fulfillments.findById(orderId)
                .orElseThrow(() -> notFound("Fulfillment not found"));
        if (fulfillment.status() == FulfillmentStatus.IN_PROGRESS) {
            return response(fulfillment);
        }
        if (fulfillment.status() != FulfillmentStatus.UNFULFILLED) {
            throw conflict("Fulfillment has already advanced");
        }
        if (refunds.isFullyRefunded(orderId)) throw conflict("Fully refunded orders cannot start fulfillment");
        fulfillment.start(clock.instant());
        return response(fulfillments.save(fulfillment));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID orderId) {
        PurchaseOrder order = lockOrder(orderId);
        requirePaid(order);
        if (fulfillments.findById(orderId).isEmpty()) {
            fulfillments.save(new OrderFulfillment(orderId, clock.instant()));
        }
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

    private static OrderFulfillmentResponse response(OrderFulfillment fulfillment) {
        return new OrderFulfillmentResponse(fulfillment.orderId(), fulfillment.status(), fulfillment.createdAt(),
                fulfillment.updatedAt(), fulfillment.inProgressAt(), fulfillment.shippedAt(), fulfillment.deliveredAt());
    }

    private static OrderException notFound(String message) {
        return new OrderException(OrderException.Code.NOT_FOUND, message);
    }
    private static OrderException conflict(String message) {
        return new OrderException(OrderException.Code.CONFLICT, message);
    }
}
