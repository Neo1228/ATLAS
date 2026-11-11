package com.atlas.order.service.impl;

import com.atlas.cart.dto.request.ReorderCartRequest;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.persistence.state.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.exception.OrderException;
import com.atlas.order.dto.request.OrderReorderRequest;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.projection.ExistingReorderRequest;
import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.repository.JdbcOrderReorderRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.OrderReorderService;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderReorderServiceImpl implements OrderReorderService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final InventoryService inventory;
    private final CartService carts;
    private final SellerSkuService sellerSkus;
    private final JdbcOrderReorderRepository reorderRequests;
    private final Clock clock;

    public OrderReorderServiceImpl(OrderRepository orders, OrderItemRepository items,
                                   InventoryService inventory, CartService carts,
                                   JdbcOrderReorderRepository reorderRequests, SellerSkuService sellerSkus,
                                   Clock clock) {
        this.orders = Objects.requireNonNull(orders);
        this.items = Objects.requireNonNull(items);
        this.inventory = Objects.requireNonNull(inventory);
        this.carts = Objects.requireNonNull(carts);
        this.reorderRequests = Objects.requireNonNull(reorderRequests);
        this.sellerSkus = Objects.requireNonNull(sellerSkus);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public CartResponse reorder(OrderReorderRequest request) {
        if (request == null) throw new OrderException(OrderException.Code.INVALID_REQUEST, "Reorder request required");
        UUID orderId = request.orderId();
        String idempotencyKey = request.idempotencyKey();
        boolean confirmMerge = request.confirmMerge();
        validate(orderId, idempotencyKey);

        PurchaseOrder sourceOrder = lockOrder(orderId);
        if (sourceOrder.source() != OrderSource.CART || !sourceOrder.state().isTerminal())
            throw conflict("Only terminal Cart orders can be reordered");

        ExistingReorderRequest previous = reorderRequests
                .find(sourceOrder.sellerId(), sourceOrder.buyerRef(), idempotencyKey).orElse(null);
        if (previous != null) {
            if (!previous.sourceOrderId().equals(orderId) || previous.confirmMerge() != confirmMerge)
                throw conflict("Idempotency-Key was used for different reorder request");
            return carts.find(sourceOrder.buyerRef());
        }

        List<OrderItem> sourceItems = items.findByOrderOrderIdOrderBySkuIdAsc(orderId);
        if (sourceItems.isEmpty()) throw conflict("Order has no items to reorder");
        if (sourceItems.stream().anyMatch(item -> !sourceOrder.sellerId().equals(item.sellerId())))
            throw conflict("Cart orders must contain items from one Seller");
        List<SellerSkuResponse> orderable = sellerSkus.findOrderableSellerSkus(sourceOrder.sellerId(),
                sourceItems.stream().map(OrderItem::skuId).distinct().toList(), ProductSalesChannel.ONLINE_STORE);
        Set<String> orderableSkuIds = orderable.stream().map(SellerSkuResponse::skuId)
                .collect(Collectors.toSet());
        if (sourceItems.stream().anyMatch(item -> !orderableSkuIds.contains(item.skuId()))) {
            throw new OrderException(OrderException.Code.CONFLICT, "An order SKU no longer exists");
        }

        assertInventoryAvailable(sourceOrder, sourceItems);
        boolean recorded = reorderRequests.insertIfAbsent(sourceOrder.sellerId(), sourceOrder.buyerRef(),
                idempotencyKey, orderId, confirmMerge, clock.instant());
        if (!recorded) return replayOrConflict(sourceOrder, orderId, idempotencyKey, confirmMerge);

        List<ReorderCartRequest.Item> cartItems = sourceItems.stream()
                .map(item -> new ReorderCartRequest.Item(sourceOrder.sellerId(),
                        item.productId(), item.skuId(), item.quantity()))
                .toList();
        return carts.mergeItems(new ReorderCartRequest(sourceOrder.buyerRef(), confirmMerge, cartItems));
    }

    private CartResponse replayOrConflict(PurchaseOrder sourceOrder, UUID orderId,
                                           String idempotencyKey, boolean confirmMerge) {
        ExistingReorderRequest existing = reorderRequests
                .find(sourceOrder.sellerId(), sourceOrder.buyerRef(), idempotencyKey).orElseThrow(() ->
                        new IllegalStateException("Reorder idempotency row disappeared"));
        if (!existing.sourceOrderId().equals(orderId) || existing.confirmMerge() != confirmMerge)
            throw conflict("Idempotency-Key used different reorder request");
        return carts.find(sourceOrder.buyerRef());
    }

    private void assertInventoryAvailable(PurchaseOrder sourceOrder, List<OrderItem> items) {
        List<ReserveInventoryBatchRequest.Item> lines = items.stream()
                .map(item -> new ReserveInventoryBatchRequest.Item(
                        sourceOrder.sellerId(), item.skuId(), item.quantity()))
                .toList();
        if (inventory.findFulfillmentLocation(sourceOrder.sellerId(), lines).isEmpty()) {
            throw new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK,
                    "no single active fulfillment location has sufficient stock for seller "
                            + sourceOrder.sellerId());
        }
    }

    private PurchaseOrder lockOrder(UUID orderId) {
        return orders.findWithLockByOrderId(orderId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Order not found"));
    }

    private static void validate(UUID orderId, String idempotencyKey) {
        if (orderId == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 256)
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "orderId and valid Idempotency-Key are required");
    }

    private static OrderException conflict(String message) {
        return new OrderException(OrderException.Code.CONFLICT, message);
    }
}
