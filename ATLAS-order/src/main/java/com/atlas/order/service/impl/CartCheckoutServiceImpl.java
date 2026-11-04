package com.atlas.order.service.impl;

import com.atlas.cart.dto.response.CartItemResponse;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class CartCheckoutServiceImpl implements CartCheckoutService {
    private final CartService carts;
    private final SellerSkuService sellerSkus;
    private final InventoryService inventory;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderService orderService;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration checkoutWindow;

    public CartCheckoutServiceImpl(CartService carts, SellerSkuService sellerSkus, InventoryService inventory,
                                  OrderRepository orders, OrderItemRepository orderItems, OrderService orderService,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow) {
        this.carts = Objects.requireNonNull(carts);
        this.sellerSkus = Objects.requireNonNull(sellerSkus);
        this.inventory = Objects.requireNonNull(inventory);
        this.orders = Objects.requireNonNull(orders);
        this.orderItems = Objects.requireNonNull(orderItems);
        this.orderService = Objects.requireNonNull(orderService);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
        this.checkoutWindow = Objects.requireNonNull(checkoutWindow);
        if (checkoutWindow.isZero() || checkoutWindow.isNegative()) {
            throw new IllegalArgumentException("checkoutWindow must be positive");
        }
    }

    @Override
    public OrderResponse checkout(UUID sellerId, String buyerRef, String idempotencyKey) {
        validate(sellerId, buyerRef, idempotencyKey);
        UUID orderId = transactions.execute(status -> {
            PurchaseOrder existing = findExisting(sellerId, buyerRef, idempotencyKey);
            if (existing != null) return existing.orderId();

            CartResponse cart = carts.lockForCheckout(sellerId, buyerRef);
            // Recheck after acquiring the cart lock: a concurrent identical checkout may have committed meanwhile.
            existing = findExisting(sellerId, buyerRef, idempotencyKey);
            if (existing != null) return existing.orderId();
            if (cart.items().isEmpty()) {
                throw new OrderException(OrderException.Code.INVALID_REQUEST, "Cannot checkout an empty cart");
            }

            List<String> skuIds = cart.items().stream().map(CartItemResponse::skuId).distinct().sorted().toList();
            Map<String, SellerSkuResponse> currentSkus = new HashMap<>();
            List<SellerSkuResponse> catalogSkus = sellerSkus.findAll(sellerId, skuIds);
            if (catalogSkus != null) {
                for (SellerSkuResponse sku : catalogSkus) {
                    if (sku != null && sku.skuId() != null && currentSkus.putIfAbsent(sku.skuId(), sku) != null) {
                        throw new OrderException(OrderException.Code.CONFLICT,
                                "Catalog returned duplicate seller SKUs for checkout");
                    }
                }
            }
            List<Line> lines = cart.items().stream()
                    .sorted(Comparator.comparing(CartItemResponse::skuId))
                    .map(item -> snapshot(sellerId, item, currentSkus.get(item.skuId())))
                    .toList();

            Instant now = clock.instant();
            PurchaseOrder order = orders.save(new PurchaseOrder(sellerId, buyerRef, idempotencyKey,
                    OrderState.RESERVED, now.plus(checkoutWindow), now, now));
            List<OrderItem> snapshots = lines.stream().map(line -> new OrderItem(order,
                    line.sku().productId(), sellerId, line.sku().skuId(), line.sku().productName(),
                    line.cartItem().quantity(), line.sku().priceWon())).toList();
            long total = 0;
            for (OrderItem item : snapshots) total = Math.addExact(total, item.lineTotalWon());
            orderItems.saveAll(snapshots);
            List<ReserveInventoryBatchRequest.Item> reservations = lines.stream()
                    .map(line -> new ReserveInventoryBatchRequest.Item(sellerId,
                            line.sku().skuId(), line.cartItem().quantity())).toList();
            inventory.reserveAll(new ReserveInventoryBatchRequest(order.orderId(), null, reservations));
            carts.clearForCheckout(sellerId, buyerRef);
            return order.orderId();
        });
        return orderService.find(orderId);
    }

    private PurchaseOrder findExisting(UUID sellerId, String buyerRef, String key) {
        return orders.findBySellerIdAndBuyerRefAndCheckoutKey(sellerId, buyerRef, key).orElse(null);
    }

    private Line snapshot(UUID sellerId, CartItemResponse cartItem, SellerSkuResponse sku) {
        if (sku == null || !sellerId.equals(sku.sellerId()) || sku.skuId() == null
                || !cartItem.skuId().equals(sku.skuId()) || !Objects.equals(cartItem.productId(), sku.productId())) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Cart item no longer matches its seller SKU product");
        }
        return new Line(cartItem, sku);
    }

    private static void validate(UUID sellerId, String buyerRef, String idempotencyKey) {
        if (sellerId == null || buyerRef == null || buyerRef.isBlank() || buyerRef.length() > 256
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 256) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "seller, buyer, and a valid idempotency key are required");
        }
    }

    private record Line(CartItemResponse cartItem, SellerSkuResponse sku) { }
}
