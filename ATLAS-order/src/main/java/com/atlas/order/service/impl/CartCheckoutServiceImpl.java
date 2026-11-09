package com.atlas.order.service.impl;

import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.cart.dto.response.CartItemResponse;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.catalog.dto.response.SellerCouponResponse;
import com.atlas.catalog.service.SellerCouponService;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.request.SellerCouponRequest;
import com.atlas.order.dto.response.OrderItemResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.BuyerCheckoutRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderService;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class CartCheckoutServiceImpl implements CartCheckoutService {
    private final CartService carts;
    private final SellerSkuService sellerSkus;
    private final SellerCouponService sellerCoupons;
    private final InventoryService inventory;
    private final BuyerCheckoutRepository checkouts;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderService orderService;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration checkoutWindow;

    public CartCheckoutServiceImpl(CartService carts, SellerSkuService sellerSkus,
                                  SellerCouponService sellerCoupons, InventoryService inventory,
                                  BuyerCheckoutRepository checkouts, OrderRepository orders,
                                  OrderItemRepository orderItems, OrderService orderService,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow) {
        this.carts = Objects.requireNonNull(carts);
        this.sellerSkus = Objects.requireNonNull(sellerSkus);
        this.sellerCoupons = Objects.requireNonNull(sellerCoupons);
        this.inventory = Objects.requireNonNull(inventory);
        this.checkouts = Objects.requireNonNull(checkouts);
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
    public CheckoutResponse checkout(String buyerRef, String idempotencyKey) {
        return checkout(buyerRef, idempotencyKey, List.of());
    }

    @Override
    public CheckoutResponse checkout(String buyerRef, String idempotencyKey,
                                     List<SellerCouponRequest> selections) {
        validate(buyerRef, idempotencyKey);
        Map<UUID, String> couponCodes = normalizeCouponSelections(selections);
        UUID checkoutId = transactions.execute(status ->
                checkoutLocked(buyerRef, idempotencyKey, couponCodes));
        if (checkoutId == null) {
            throw new OrderException(OrderException.Code.CONFLICT, "Checkout transaction produced no checkout id");
        }
        return toResponse(checkoutId);
    }

    private UUID checkoutLocked(String buyerRef, String idempotencyKey, Map<UUID, String> couponCodes) {
        CartResponse cart = carts.lockForCheckout(buyerRef);
        BuyerCheckout existing = checkouts.findByBuyerRefAndIdempotencyKey(buyerRef, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            List<PurchaseOrder> childOrders = orders.findAllByCheckoutIdOrderBySellerIdAsc(existing.checkoutId());
            if (childOrders.isEmpty()) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Checkout key exists without child orders");
            }
            if (!couponSelectionsMatch(childOrders, couponCodes)) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Checkout key was already used with different coupon selections");
            }
            if (!cart.items().isEmpty() && !cartMatchesOrderItemSnapshot(cart.items(), childOrders)) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Checkout key was already used for different cart contents");
            }
            return existing.checkoutId();
        }
        if (cart.items().isEmpty()) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Cannot checkout an empty cart");
        }
        Instant now = clock.instant();

        TreeMap<UUID, List<CartItemResponse>> sellerItems = groupItems(cart.items());
        if (!sellerItems.keySet().containsAll(couponCodes.keySet())) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "A selected coupon Seller has no cart items");
        }
        List<SellerLines> snapshotsBySeller = new ArrayList<>(sellerItems.size());
        long checkoutTotal = 0;
        for (Map.Entry<UUID, List<CartItemResponse>> seller : sellerItems.entrySet()) {
            List<CartItemResponse> items = seller.getValue().stream()
                    .sorted(Comparator.comparing(CartItemResponse::skuId)).toList();
            List<String> skuIds = items.stream().map(CartItemResponse::skuId).toList();
            List<SellerSkuResponse> found = sellerSkus.findOrderableSellerSkus(
                    seller.getKey(), skuIds, ProductSalesChannel.ONLINE_STORE, now);
            Map<String, SellerSkuResponse> bySku = new HashMap<>();
            if (found != null) {
                for (SellerSkuResponse sku : found) {
                    if (sku == null || sku.skuId() == null
                            || bySku.putIfAbsent(sku.skuId(), sku) != null) {
                        throw new OrderException(OrderException.Code.CONFLICT,
                                "Catalog returned invalid or duplicate seller SKUs for checkout");
                    }
                }
            }
            List<Line> lines = items.stream()
                    .map(item -> snapshot(seller.getKey(), item, bySku.get(item.skuId())))
                    .toList();
            String couponCode = couponCodes.get(seller.getKey());
            SellerCouponResponse coupon = couponCode == null ? null
                    : sellerCoupons.findActive(seller.getKey(), couponCode, now);
            if (coupon != null) {
                if (lines.stream().anyMatch(line -> line.sku().effectivePriceWon() < line.sku().priceWon())) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "Seller coupon cannot be combined with an active SKU sale price");
                }
                Map<String, Long> allocations = allocateCouponDiscount(lines, coupon.discountAmountWon());
                lines = lines.stream().map(line -> new Line(line.cartItem(), line.sku(),
                        allocations.getOrDefault(line.sku().skuId(), 0L))).toList();
            }
            long sellerTotal = 0;
            for (Line line : lines) sellerTotal = Math.addExact(sellerTotal, line.lineTotalWon());
            checkoutTotal = Math.addExact(checkoutTotal, sellerTotal);
            snapshotsBySeller.add(new SellerLines(seller.getKey(), lines, coupon));
        }

        BuyerCheckout checkout = checkouts.save(new BuyerCheckout(buyerRef, idempotencyKey,
                BuyerCheckoutState.RESERVED, checkoutTotal, now, now));
        UUID checkoutId = checkout.checkoutId();
        for (SellerLines seller : snapshotsBySeller) {
            PurchaseOrder childOrder = new PurchaseOrder(seller.sellerId(), buyerRef, idempotencyKey,
                    OrderState.RESERVED, now.plus(checkoutWindow), now, now);
            childOrder.setCheckoutId(checkoutId);
            if (seller.coupon() != null) {
                childOrder.applyCoupon(seller.coupon().couponId(), seller.coupon().code(),
                        seller.coupon().discountAmountWon());
            }
            PurchaseOrder savedChild = orders.save(childOrder);

            List<OrderItem> snapshots = seller.lines().stream().map(line -> new OrderItem(savedChild,
                    line.sku().productId(), seller.sellerId(), line.sku().skuId(), line.sku().productName(),
                    line.cartItem().quantity(), line.sku().effectivePriceWon(), line.couponDiscountWon())).toList();
            orderItems.saveAll(snapshots);

            List<ReserveInventoryBatchRequest.Item> reservations = seller.lines().stream()
                    .map(line -> new ReserveInventoryBatchRequest.Item(seller.sellerId(),
                            line.sku().skuId(), line.cartItem().quantity()))
                    .toList();
            List<ReservationResponse> savedReservations = inventory.reserveAll(
                    new ReserveInventoryBatchRequest(savedChild.orderId(), null, reservations));
            UUID locationId = savedReservations.stream().map(ReservationResponse::locationId)
                    .filter(Objects::nonNull).findFirst()
                    .orElseThrow(() -> new OrderException(OrderException.Code.CONFLICT,
                            "Inventory reservations did not include a fulfillment location"));
            if (savedReservations.size() != reservations.size() || savedReservations.stream()
                    .anyMatch(reservation -> !locationId.equals(reservation.locationId()))) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Inventory reservations disagree on fulfillment location");
            }
            savedChild.setFulfillmentLocationId(locationId);
            if (seller.coupon() != null) {
                sellerCoupons.reserveRedemption(seller.sellerId(), seller.coupon().couponId(),
                        savedChild.orderId(), now);
            }
        }
        carts.clearForCheckout(buyerRef);
        return checkoutId;
    }

    private CheckoutResponse toResponse(UUID checkoutId) {
        BuyerCheckout checkout = checkouts.findById(checkoutId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Buyer checkout not found"));
        List<PurchaseOrder> childOrders = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
        List<OrderResponse> childResponses = childOrders.stream()
                .map(order -> orderService.find(order.orderId()))
                .toList();
        return new CheckoutResponse(checkout.checkoutId(), checkout.buyerRef(), checkout.state(),
                checkout.totalWon(), childResponses);
    }

    private boolean cartMatchesOrderItemSnapshot(List<CartItemResponse> cartItems, List<PurchaseOrder> childOrders) {
        List<Fact> cartFacts = cartItems.stream()
                .map(item -> new Fact(item.sellerId(), item.skuId(), item.quantity(), item.priceWon()))
                .sorted(FACT_ORDER).toList();
        List<Fact> existingFacts = new ArrayList<>();
        for (PurchaseOrder order : childOrders) {
            for (OrderItem item : orderItems.findByOrderOrderIdOrderBySkuIdAsc(order.orderId())) {
                existingFacts.add(new Fact(item.sellerId(), item.skuId(), item.quantity(), item.unitPriceWon()));
            }
        }
        existingFacts.sort(FACT_ORDER);
        return cartFacts.equals(existingFacts);
    }

    private static Map<UUID, String> normalizeCouponSelections(List<SellerCouponRequest> selections) {
        if (selections == null || selections.isEmpty()) return Map.of();
        Map<UUID, String> couponCodes = new HashMap<>();
        for (SellerCouponRequest selection : selections) {
            if (selection == null || selection.sellerId() == null || selection.code() == null) {
                throw new OrderException(OrderException.Code.INVALID_REQUEST, "Invalid seller coupon selection");
            }
            String code = selection.code().trim().toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z0-9_-]{3,32}")) {
                throw new OrderException(OrderException.Code.INVALID_REQUEST, "Invalid seller coupon code");
            }
            if (couponCodes.putIfAbsent(selection.sellerId(), code) != null) {
                throw new OrderException(OrderException.Code.INVALID_REQUEST,
                        "Only one coupon may be selected per Seller");
            }
        }
        return Map.copyOf(couponCodes);
    }

    private static boolean couponSelectionsMatch(List<PurchaseOrder> orders, Map<UUID, String> couponCodes) {
        Map<UUID, String> savedCodes = new HashMap<>();
        for (PurchaseOrder order : orders) {
            if (order.sellerCouponCode() != null) savedCodes.put(order.sellerId(), order.sellerCouponCode());
        }
        return savedCodes.equals(couponCodes);
    }

    private static Map<String, Long> allocateCouponDiscount(List<Line> lines, long discountAmountWon) {
        long subtotalWon = 0;
        for (Line line : lines) {
            subtotalWon = Math.addExact(subtotalWon,
                    Math.multiplyExact(line.sku().effectivePriceWon(), line.cartItem().quantity()));
        }
        if (discountAmountWon <= 0 || discountAmountWon >= subtotalWon) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "Coupon amount must be positive and less than SellerOrder subtotal");
        }

        BigInteger total = BigInteger.valueOf(subtotalWon);
        BigInteger discount = BigInteger.valueOf(discountAmountWon);
        List<CouponAllocation> allocations = new ArrayList<>(lines.size());
        Map<String, Long> amountBySku = new HashMap<>();
        long allocatedWon = 0;
        for (Line line : lines) {
            long lineSubtotalWon = Math.multiplyExact(line.sku().effectivePriceWon(),
                    line.cartItem().quantity());
            BigInteger[] share = discount.multiply(BigInteger.valueOf(lineSubtotalWon))
                    .divideAndRemainder(total);
            long baseWon = share[0].longValueExact();
            allocatedWon = Math.addExact(allocatedWon, baseWon);
            amountBySku.put(line.sku().skuId(), baseWon);
            allocations.add(new CouponAllocation(line.sku().skuId(), share[1]));
        }

        int remainingWon = Math.toIntExact(discountAmountWon - allocatedWon);
        allocations.sort(Comparator.comparing(CouponAllocation::remainder).reversed()
                .thenComparing(CouponAllocation::skuId));
        for (int index = 0; index < remainingWon; index++) {
            String skuId = allocations.get(index).skuId();
            amountBySku.put(skuId, Math.addExact(amountBySku.get(skuId), 1));
        }
        for (Line line : lines) {
            long allocated = amountBySku.get(line.sku().skuId());
            long maximumUnitDiscount = allocated / line.cartItem().quantity()
                    + (allocated % line.cartItem().quantity() == 0 ? 0 : 1);
            if (maximumUnitDiscount >= line.sku().effectivePriceWon()) {
                throw new OrderException(OrderException.Code.INVALID_REQUEST,
                        "Coupon allocation must leave every item unit refundable for a positive amount");
            }
        }
        return Map.copyOf(amountBySku);
    }
    private record CouponAllocation(String skuId, BigInteger remainder) { }

    private static TreeMap<UUID, List<CartItemResponse>> groupItems(List<CartItemResponse> items) {
        TreeMap<UUID, List<CartItemResponse>> grouped = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        for (CartItemResponse item : items) {
            if (item == null || item.sellerId() == null || item.skuId() == null || item.skuId().isBlank()
                    || item.quantity() <= 0) {
                throw new OrderException(OrderException.Code.CONFLICT, "Cart contains an invalid item");
            }
            String uniqueKey = item.sellerId() + "\u0000" + item.skuId();
            if (!keys.add(uniqueKey)) {
                throw new OrderException(OrderException.Code.CONFLICT,
                        "Cart contains duplicate seller SKU entries");
            }
            grouped.computeIfAbsent(item.sellerId(), ignored -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private static Line snapshot(UUID sellerId, CartItemResponse cartItem, SellerSkuResponse sku) {
        if (sku == null || !sellerId.equals(sku.sellerId()) || sku.skuId() == null
                || !cartItem.skuId().equals(sku.skuId()) || sku.productId() == null
                || !Objects.equals(cartItem.productId(), sku.productId()) || sku.productName() == null
                || sku.productName().isBlank() || sku.effectivePriceWon() <= 0) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Cart item no longer matches its seller SKU product");
        }
        return new Line(cartItem, sku, 0);
    }

    private static void validate(String buyerRef, String idempotencyKey) {
        if (buyerRef == null || buyerRef.isBlank() || buyerRef.length() > 256
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 256) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "buyer and valid idempotency key are required");
        }
    }

    private static final Comparator<Fact> FACT_ORDER = Comparator
            .comparing(Fact::sellerId).thenComparing(Fact::skuId).thenComparingInt(Fact::quantity).thenComparingLong(Fact::priceWon);

    private record Fact(UUID sellerId, String skuId, int quantity, long priceWon) { }
    private record Line(CartItemResponse cartItem, SellerSkuResponse sku, long couponDiscountWon) {
        private long lineTotalWon() {
            return Math.subtractExact(Math.multiplyExact(sku.effectivePriceWon(), cartItem.quantity()),
                    couponDiscountWon);
        }
    }
    private record SellerLines(UUID sellerId, List<Line> lines, SellerCouponResponse coupon) { }
}
