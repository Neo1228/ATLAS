package com.atlas.order.service.impl;

import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.dto.request.InventoryRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.order.dto.response.CartItemResponse;
import com.atlas.order.dto.response.CartResponse;
import com.atlas.order.service.CartService;
import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.service.SellerCouponService;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.LocationReservationRequest.Item;
import com.atlas.order.dto.request.LocationReservationRequest.LocationAllocation;
import com.atlas.order.dto.request.LocationReservationRequest;
import com.atlas.order.dto.request.SellerCouponRequest;
import com.atlas.order.dto.response.CheckoutPreviewResponse;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.exception.LocationClientException;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.BuyerCheckout;
import com.atlas.order.persistence.entity.OrderItem;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.model.OrderState;
import com.atlas.order.repository.BuyerCheckoutRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutFinalizationService;
import com.atlas.order.service.LocationClient;
import com.atlas.order.service.LocationReservationService;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final Optional<LocationReservationService> locationReservations;
    private final Optional<CheckoutFinalizationService> reservationFinalizer;

    private final boolean splitAllocationEnabled;
    private final Optional<LocationClient> locationClient;
    private final int maxOpenCheckoutsPerBuyer;
    private final int maxReservedUnitsPerBuyer;
    private final Duration expiredHoldCooldown;
    private final Duration cancelledHoldBudget;




    public CartCheckoutServiceImpl(CartService carts, SellerSkuService sellerSkus,
                                  SellerCouponService sellerCoupons, InventoryService inventory,
                                  BuyerCheckoutRepository checkouts, OrderRepository orders,
                                  OrderItemRepository orderItems, OrderService orderService,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  @Value("${atlas.checkout-window:PT10M}") Duration checkoutWindow,
            // 단일 location으로 SellerOrder를 처리할 수 없을 때 여러 location으로 나눠 예약할지 여부(EXP-054).
            @Value("${atlas.order-inventory-reservation.split-allocation-enabled:false}") boolean splitAllocationEnabled,
            // Buyer 한 명이 동시에 잡을 수 있는 미결 checkout 수와 그 예약 수량 합의 상한(ADR-0113). 기본값은 여기 한 곳이고
            // 환경 변수 ATLAS_CHECKOUT_BUYER_LIMIT_OPEN_CHECKOUTS, ATLAS_CHECKOUT_BUYER_LIMIT_RESERVED_UNITS로 바꾼다.
            @Value("${atlas.checkout-buyer-limit.open-checkouts:1}") int maxOpenCheckoutsPerBuyer,
            @Value("${atlas.checkout-buyer-limit.reserved-units:50}") int maxReservedUnitsPerBuyer,
            // 결제하지 않고 만료된 hold의 SKU를 같은 Buyer가 다시 잡지 못하는 시간(ADR-0113 결정 6). 수량 합 상한만으로는
            // 재고 50개 이하 SKU를 통째로 잡고 만료 뒤 되풀이할 수 있다. 환경 변수 ATLAS_CHECKOUT_BUYER_LIMIT_EXPIRED_HOLD_COOLDOWN.
            @Value("${atlas.checkout-buyer-limit.expired-hold-cooldown:PT30M}") Duration expiredHoldCooldown,
            // Buyer가 결제하지 않고 취소한 hold의 SKU 쿨다운(ADR-0113 결정 6을 취소로 넓힌다). 만료 쿨다운과 같은 구간
            // 안에 취소한 checkout들이 그 SKU를 잡고 있던 시간의 합이 이 값 이상이면 거절한다. 취소를 세지 않으면 window
            // 끝 직전에 취소하고 다시 잡기를 끝없이 되풀이할 수 있다. 한 번에 막지 않는 것은 마음을 바꿔 곧바로 취소하고
            // 다시 사는 흐름을 남기기 위해서다. 구간은 expired-hold-cooldown을 함께 쓰므로 그 값이 0이면 이 검사도 꺼진다.
            // 0을 주면 취소 한 번에 쿨다운이 걸린다. 환경 변수 ATLAS_CHECKOUT_BUYER_LIMIT_CANCELLED_HOLD_BUDGET.
            @Value("${atlas.checkout-buyer-limit.cancelled-hold-budget:PT5M}") Duration cancelledHoldBudget,
            Optional<LocationClient> locationClient,
            Optional<LocationReservationService> locationReservations,
            Optional<CheckoutFinalizationService> reservationFinalizer) {
        this.locationClient = locationClient;
        this.locationReservations = locationReservations;
        this.reservationFinalizer = reservationFinalizer;
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
        this.splitAllocationEnabled = splitAllocationEnabled;
        if (checkoutWindow.isZero() || checkoutWindow.isNegative()) {
            throw new IllegalArgumentException("checkoutWindow must be positive");
        }
        if (maxOpenCheckoutsPerBuyer <= 0 || maxReservedUnitsPerBuyer <= 0 || expiredHoldCooldown.isNegative()
                || cancelledHoldBudget.isNegative()) {
            throw new IllegalArgumentException("Buyer checkout limits must be positive");
        }
        this.maxOpenCheckoutsPerBuyer = maxOpenCheckoutsPerBuyer;
        this.maxReservedUnitsPerBuyer = maxReservedUnitsPerBuyer;
        this.expiredHoldCooldown = expiredHoldCooldown;
        this.cancelledHoldBudget = cancelledHoldBudget;
    }

    @Override
    public CheckoutResponse checkout(String buyerRef, String idempotencyKey) {
        return checkout(buyerRef, idempotencyKey, List.of());
    }

    @Override
    public CheckoutResponse checkout(String buyerRef, String idempotencyKey,
                                     List<SellerCouponRequest> selections) {
        return checkout(buyerRef, idempotencyKey, selections, null);
    }

    @Override
    public CheckoutResponse checkoutWithinCap(String buyerRef, String idempotencyKey, long spendingCapWon) {
        if (spendingCapWon <= 0) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Spending cap must be positive");
        }
        return checkout(buyerRef, idempotencyKey, List.of(), spendingCapWon);
    }

    @Override
    public CheckoutPreviewResponse preview(String buyerRef) {
        if (buyerRef == null || buyerRef.isBlank() || buyerRef.length() > 256) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "buyer is required");
        }
        CartResponse cart = carts.find(buyerRef);
        if (cart.items().isEmpty()) return new CheckoutPreviewResponse(buyerRef, List.of(), 0);
        PricedCart priced;
        try {
            priced = price(cart.items(), Map.of(), clock.instant());
        } catch (ArithmeticException overflow) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "Checkout amount exceeds the supported range");
        }
        List<CheckoutPreviewResponse.Line> lines = priced.sellers().stream()
                .flatMap(seller -> seller.lines().stream())
                .map(line -> new CheckoutPreviewResponse.Line(line.sku().sellerId(), line.sku().productId(),
                        line.sku().skuId(), line.sku().productName(), line.cartItem().quantity(),
                        line.sku().effectivePriceWon(), line.lineTotalWon()))
                .toList();
        return new CheckoutPreviewResponse(buyerRef, lines, priced.totalWon());
    }

    private CheckoutResponse checkout(String buyerRef, String idempotencyKey,
                                      List<SellerCouponRequest> selections, Long spendingCapWon) {
        validate(buyerRef, idempotencyKey);
        Map<UUID, String> couponCodes = normalizeCouponSelections(selections);
        UUID checkoutId;
        RemoteStock stock = locationReservationEnabled() ? remoteCandidates(buyerRef) : RemoteStock.NONE;
        try {
            checkoutId = transactions.execute(status ->
                    checkoutLocked(buyerRef, idempotencyKey, couponCodes, stock, spendingCapWon));
        } catch (ArithmeticException overflow) {
            // 단가에는 상한이 없으므로 Buyer 예약 수량 상한 안에서도 가격 × 수량이 long 을 넘칠 수 있다.
            // 이것은 클라이언트가 만든 입력 오류이며 500 이 아니라 400 이어야 한다.
            // 카트 덧셈(CartServiceImpl)과 환불 경로도 같은 방식으로 변환한다.
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "Checkout amount exceeds the supported range");
        }
        if (checkoutId == null) {
            throw new OrderException(OrderException.Code.CONFLICT, "Checkout transaction produced no checkout id");
        }
        if (locationReservationEnabled()) {
            finishLocationReservation(checkoutId);
        }
        return toResponse(checkoutId);
    }

    private UUID checkoutLocked(String buyerRef, String idempotencyKey, Map<UUID, String> couponCodes,
                                RemoteStock stock, Long spendingCapWon) {
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
            requireWithinCap(existing.totalWon(), spendingCapWon);
            return existing.checkoutId();
        }
        if (cart.items().isEmpty()) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Cannot checkout an empty cart");
        }
        Instant now = clock.instant();
        enforceBuyerHoldLimits(buyerRef, cart.items(), now);
        PricedCart priced = price(cart.items(), couponCodes, now);
        requireWithinCap(priced.totalWon(), spendingCapWon);
        List<SellerLines> snapshotsBySeller = priced.sellers();
        long checkoutTotal = priced.totalWon();

        BuyerCheckout checkout = checkouts.save(new BuyerCheckout(buyerRef, idempotencyKey,
                locationReservationEnabled() ? BuyerCheckoutState.RESERVATION_PENDING : BuyerCheckoutState.RESERVED,
                checkoutTotal, now, now));
        UUID checkoutId = checkout.checkoutId();
        for (SellerLines seller : snapshotsBySeller) {
            PurchaseOrder childOrder = new PurchaseOrder(seller.sellerId(), buyerRef, idempotencyKey,
                    locationReservationEnabled() ? OrderState.RESERVATION_PENDING : OrderState.RESERVED,
                    now.plus(checkoutWindow), now, now);
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

            List<InventoryRequest.ReserveInventoryBatch.Item> reservations = seller.lines().stream()
                    .map(line -> new InventoryRequest.ReserveInventoryBatch.Item(seller.sellerId(),
                            line.sku().skuId(), line.cartItem().quantity()))
                    .toList();
            UUID locationId;
            if (locationReservationEnabled()) {
                Map<String, Integer> required = new TreeMap<>();
                for (InventoryRequest.ReserveInventoryBatch.Item item : reservations) {
                    required.merge(item.skuId(), item.quantity(), Math::addExact);
                }
                List<LocationAllocationPolicy.Plan> plan = LocationAllocationPolicy.plan(required,
                        stock.candidates().getOrDefault(seller.sellerId(), List.of()), splitAllocationEnabled);
                if (plan.isEmpty()) {
                    if (stock.unreachableSellers().contains(seller.sellerId())) {
                        // 조회 실패한 위치가 있었다면 재고 부족이 아니라 가용 수량을 확인하지 못한 것이므로 재시도 가능한 오류로 알린다.
                        throw new OrderException(OrderException.Code.INVENTORY_UNAVAILABLE,
                                "Inventory availability could not be confirmed; retry the checkout");
                    }
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "No fulfillment location can serve this SellerOrder");
                }
                // 여러 location으로 나뉘면 단일 출고지가 없으므로 location별 내역은 workflow에만 기록한다.
                locationId = plan.size() == 1 ? plan.getFirst().locationId() : null;
                LocationReservationRequest request = new LocationReservationRequest(
                        savedChild.orderId(), plan.stream().map(allocation -> new LocationAllocation(
                                allocation.locationId(), allocation.lines().entrySet().stream()
                                .map(line -> new Item(seller.sellerId(), line.getKey(), line.getValue()))
                                .toList())).toList());
                String reservationKey = reservationKey(savedChild.orderId());
                ReservationWorkflowResponse.LocationReservation staged = requireLocationReservations()
                        .stage(reservationKey, request);
                savedChild.setReservationWorkflowId(staged.workflowId());
            } else {
                List<InventoryResponse.Reservation> savedReservations = inventory.reserveAll(
                        new InventoryRequest.ReserveInventoryBatch(savedChild.orderId(), null, reservations));
                locationId = savedReservations.stream().map(InventoryResponse.Reservation::locationId)
                        .filter(Objects::nonNull).findFirst()
                        .orElseThrow(() -> new OrderException(OrderException.Code.CONFLICT,
                                "Inventory reservations did not include a fulfillment location"));
                if (savedReservations.size() != reservations.size() || savedReservations.stream()
                        .anyMatch(reservation -> !locationId.equals(reservation.locationId()))) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "Inventory reservations disagree on fulfillment location");
                }
            }
            savedChild.setFulfillmentLocationId(locationId);
            if (seller.coupon() != null) {
                sellerCoupons.reserveRedemption(seller.sellerId(), seller.coupon().couponId(),
                        savedChild.orderId(), now);
            }
        }
        if (!locationReservationEnabled()) {
            carts.clearForCheckout(buyerRef);
        }
        return checkoutId;
    }

    /**
     * 장바구니를 현재 판매 가능한 SKU 가격으로 계산한다. checkout과 미리보기가 같은 계산을 쓰므로
     * 미리보기 금액과 checkout 금액이 갈라지지 않는다(같은 시각, 같은 장바구니라면 같은 값).
     */
    private PricedCart price(List<CartItemResponse> cartItems, Map<UUID, String> couponCodes, Instant now) {
        TreeMap<UUID, List<CartItemResponse>> sellerItems = groupItems(cartItems);
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
            List<CatalogResponse.SellerSku> found = sellerSkus.findOrderableSellerSkus(
                    seller.getKey(), skuIds, ProductSalesChannel.ONLINE_STORE, now);
            Map<String, CatalogResponse.SellerSku> bySku = new HashMap<>();
            if (found != null) {
                for (CatalogResponse.SellerSku sku : found) {
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
            CatalogResponse.SellerCoupon coupon = couponCode == null ? null
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
        return new PricedCart(List.copyOf(snapshotsBySeller), checkoutTotal);
    }

    /**
     * 같은 Buyer가 미결 checkout을 겹쳐 열거나 재고를 한도 이상 잡지 못하게 한다(ADR-0113).
     * 원격 예약 모드에서는 RESERVED가 될 때까지 cart를 비우지 않으므로 이 검사가 없으면
     * 새 Idempotency-Key로 같은 cart를 두 번 예약할 수 있다.
     *
     * <p>확인과 생성 사이에 다른 checkout이 끼어들지 않는다는 전제는 {@code checkoutLocked} 첫 줄의
     * Buyer cart 행 잠금({@code SELECT ... FOR UPDATE})에 기댄다. 같은 Buyer의 cart checkout은 그 잠금에서
     * 직렬화된다. cart를 거치지 않고 BuyerCheckout을 만드는 경로가 생기면 이 전제가 깨진다.
     */
    private void enforceBuyerHoldLimits(String buyerRef, List<CartItemResponse> items, Instant now) {
        BuyerCheckoutRepository.OpenHold hold = checkouts.findOpenHold(buyerRef, now);
        if (hold.getOpenCheckouts() >= maxOpenCheckoutsPerBuyer) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Buyer already has an open checkout; pay or cancel it before starting another");
        }
        long requestedUnits = 0;
        for (CartItemResponse item : items) requestedUnits += item.quantity();
        if (hold.getReservedUnits() + requestedUnits > maxReservedUnitsPerBuyer) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "Checkout would hold more than " + maxReservedUnitsPerBuyer + " units for this Buyer");
        }
        if (!expiredHoldCooldown.isZero()) {
            List<String> skuIds = items.stream().map(CartItemResponse::skuId).toList();
            Instant since = now.minus(expiredHoldCooldown);
            checkouts.findRecentlyExpiredHeldSku(buyerRef, skuIds, since, now)
                    .ifPresent(skuId -> {
                        throw new OrderException(OrderException.Code.CONFLICT, "Buyer let an unpaid hold of SKU "
                                + skuId + " expire; retry after " + expiredHoldCooldown);
                    });
            // 취소는 재고를 바로 돌려주지만, window 끝 직전 취소를 되풀이하면 만료 쿨다운을 피해 SKU를 계속 잡는다.
            // 그래서 지난 쿨다운 구간에 취소한 hold의 보유 시간 합으로 막는다. 40분 동안 한 Buyer가 잡을 수 있는 시간은
            // 합 상한(5분) 바로 아래까지 짧게 잡고 취소한 뒤 window(10분)를 한 번 더 채우는 약 15분이다.
            checkouts.findSkuOverCancelledHoldBudget(buyerRef, skuIds, since, cancelledHoldBudget.toSeconds())
                    .ifPresent(skuId -> {
                        throw new OrderException(OrderException.Code.CONFLICT, "Buyer cancelled unpaid holds of SKU "
                                + skuId + " for " + cancelledHoldBudget + " or more; retry after "
                                + expiredHoldCooldown);
                    });
        }
    }

    private CheckoutResponse toResponse(UUID checkoutId) {
        BuyerCheckout checkout = checkouts.findById(checkoutId).orElseThrow(() -> new OrderException(
                OrderException.Code.NOT_FOUND, "Buyer checkout not found"));
        List<PurchaseOrder> childOrders = orders.findAllByCheckoutIdOrderBySellerIdAsc(checkoutId);
        List<OrderViewResponse.Order> childResponses = childOrders.isEmpty() ? List.of()
                : orderService.findAll(childOrders.stream().map(PurchaseOrder::orderId).toList());
        return new CheckoutResponse(checkout.checkoutId(), checkout.buyerRef(), checkout.state(),
                checkout.totalWon(), childResponses);
    }

    private boolean cartMatchesOrderItemSnapshot(List<CartItemResponse> cartItems, List<PurchaseOrder> childOrders) {
        List<Fact> cartFacts = cartItems.stream()
                .map(item -> new Fact(item.sellerId(), item.skuId(), item.quantity(), item.priceWon()))
                .sorted(FACT_ORDER).toList();
        List<Fact> existingFacts = childOrders.isEmpty() ? new ArrayList<>()
                : orderItems.findAllByOrderIds(childOrders.stream().map(PurchaseOrder::orderId).toList())
                        .stream()
                        .map(item -> new Fact(item.sellerId(), item.skuId(), item.quantity(), item.unitPriceWon()))
                        .collect(Collectors.toCollection(ArrayList::new));
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

    private static Line snapshot(UUID sellerId, CartItemResponse cartItem, CatalogResponse.SellerSku sku) {
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
                || idempotencyKey == null || idempotencyKey.isBlank()
                || CheckoutIdempotencyKeys.isTooLong(idempotencyKey)) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST,
                    "buyer and valid idempotency key are required");
        }
    }

    private static final Comparator<Fact> FACT_ORDER = Comparator
            .comparing(Fact::sellerId).thenComparing(Fact::skuId).thenComparingInt(Fact::quantity).thenComparingLong(Fact::priceWon);

    private record Fact(UUID sellerId, String skuId, int quantity, long priceWon) { }
    private record Line(CartItemResponse cartItem, CatalogResponse.SellerSku sku, long couponDiscountWon) {
        private long lineTotalWon() {
            return Math.subtractExact(Math.multiplyExact(sku.effectivePriceWon(), cartItem.quantity()),
                    couponDiscountWon);
        }
    }
    private record SellerLines(UUID sellerId, List<Line> lines, CatalogResponse.SellerCoupon coupon) { }
    private record PricedCart(List<SellerLines> sellers, long totalWon) { }

    /**
     * 구매자가 정한 지출 상한(ADR-0109). 주문·예약을 만들기 전, 장바구니 잠금을 잡은 같은 트랜잭션에서 서버가
     * 계산한 합계로 판정한다. 미리보기 뒤 가격이 바뀌어도 이 검사가 마지막 관문이다.
     */
    private static void requireWithinCap(long totalWon, Long spendingCapWon) {
        if (spendingCapWon != null && totalWon > spendingCapWon) {
            throw new OrderException(OrderException.Code.SPENDING_CAP_EXCEEDED,
                    "Checkout total exceeds the buyer spending cap");
        }
    }
    /**
     * 장바구니 Seller별로 활성 location의 원격 판매 가능 수량을 조회한다. 원격 예약만 재고를 차감하므로
     * 로컬 inventory_pool은 원격 모드에서 판매량을 반영하지 못한다. 조회 실패 location은 이번 계획에서 제외한다.
     */
    private RemoteStock remoteCandidates(String buyerRef) {
        if (locationClient.isEmpty()) return RemoteStock.NONE;
        Map<UUID, Set<String>> skusBySeller = new TreeMap<>();
        for (CartItemResponse item : carts.find(buyerRef).items()) {
            skusBySeller.computeIfAbsent(item.sellerId(), ignored -> new TreeSet<>()).add(item.skuId());
        }
        Map<UUID, List<LocationAllocationPolicy.Candidate>> candidates = new HashMap<>();
        Set<UUID> unreachable = new HashSet<>();
        skusBySeller.forEach((sellerId, skus) -> {
            List<LocationAllocationPolicy.Candidate> sellerCandidates = new ArrayList<>();
            for (InventoryResponse.Location location : inventory.findLocations(sellerId)) {
                if (!location.active()) continue;
                Map<String, Long> available = new TreeMap<>();
                try {
                    for (String sku : skus) {
                        available.put(sku, locationClient.get().availableStock(location.locationId(), sellerId, sku));
                    }
                } catch (LocationClientException unavailable) {
                    // 엔드포인트가 없는 위치(PERMANENT)는 원격 관리 대상이 아니므로 건너뛴다. 일시 장애만 가용 수량 미확인으로 기록한다.
                    if (unavailable.failure() == LocationClientException.Failure.RETRYABLE_UNKNOWN) {
                        unreachable.add(sellerId);
                    }
                continue;
                }
                sellerCandidates.add(new LocationAllocationPolicy.Candidate(
                        location.locationId(), location.priority(), available));
            }
            candidates.put(sellerId, sellerCandidates);
        });
        return new RemoteStock(candidates, unreachable);
    }

    private void finishLocationReservation(UUID checkoutId) {
        if (reservationFinalizer.isEmpty()) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Inventory location reservation is enabled but its finalizer is unavailable");
        }
        reservationFinalizer.get().finalizeCheckout(checkoutId);
    }

    private LocationReservationService requireLocationReservations() {
        if (locationReservations.isEmpty()) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Inventory location reservation is enabled but its coordinator is unavailable");
        }
        return locationReservations.get();
    }

    private static String reservationKey(UUID orderId) {
        return "buyer-checkout-location-v1:" + orderId;
    }

    private record RemoteStock(Map<UUID, List<LocationAllocationPolicy.Candidate>> candidates,
            Set<UUID> unreachableSellers) {
        static final RemoteStock NONE = new RemoteStock(Map.of(), Set.of());
    }

    /**
     * 분산 예약 경로를 쓰는지 여부. 같은 property(`atlas.order-inventory-reservation.enabled`)가 예약 빈들의 생성 조건이므로
     * 빈의 유무가 곧 설정 값이다. 별도 boolean 필드를 두면 둘이 어긋날 수 있다.
     */
    private boolean locationReservationEnabled() {
        return locationReservations.isPresent();
    }
}
