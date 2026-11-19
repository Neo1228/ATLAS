package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.ProductCategoryAssignment;
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.persistence.projection.ProductChannelAvailability;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.ProductCategoryAssignmentRepository;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductService;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import com.atlas.catalog.persistence.state.ProductSalesChannel;
import com.atlas.catalog.persistence.state.ProductStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private final SellerRepository sellers;
    private final ProductRepository products;
    private final ProductCategoryRepository categories;
    private final ProductCategoryAssignmentRepository categoryAssignments;
    private final SellerSkuRepository sellerSkus;
    private final InventoryService inventory;
    private final Clock clock;

    @Override
    @Transactional
    public ProductResponse createProduct(UUID sellerId, CreateProductRequest request) {
        if (sellerId == null || request == null || request.name() == null || request.name().isBlank()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product configuration");
        }
        if (!sellers.existsById(sellerId)) throw new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId);
        List<UUID> categoryIds = resolveCategoryIds(sellerId, request.categoryIds(), true);
        Product product = products.save(new Product(sellerId, categoryIds.getFirst(), request.name(), request.description()));
        List<UUID> additionalCategoryIds = categoryIds.subList(1, categoryIds.size());
        categoryAssignments.saveAll(toAssignments(sellerId, product.getProductId(), additionalCategoryIds));
        return toResponse(product, additionalCategoryIds, product.getChannelAvailability().entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().toList());
    }

    @Override
    @Transactional
    public ProductResponse updateProductCategories(UUID sellerId, UUID productId, List<UUID> categoryIds) {
        if (sellerId == null || productId == null) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product category assignment");
        }
        Product product = requireOwnedProduct(sellerId, productId);
        List<UUID> resolvedCategoryIds = resolveCategoryIds(sellerId, categoryIds, false);
        UUID primaryCategoryId = resolvedCategoryIds.contains(product.getPrimaryCategoryId())
                ? product.getPrimaryCategoryId() : resolvedCategoryIds.getFirst();
        product.assignPrimaryCategory(primaryCategoryId);
        products.save(product);
        List<UUID> additionalCategoryIds = resolvedCategoryIds.stream()
                .filter(categoryId -> !categoryId.equals(primaryCategoryId)).toList();
        categoryAssignments.deleteAllBySellerIdAndProductId(sellerId, productId);
        categoryAssignments.saveAll(toAssignments(sellerId, productId, additionalCategoryIds));
        return toResponse(product, additionalCategoryIds, product.getChannelAvailability().entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().toList());
    }

    @Override
    @Transactional
    public ProductResponse updateStatus(UUID sellerId, UUID productId, ProductStatus status) {
        if (sellerId == null || productId == null || status == null)
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product status update");
        Product product = requireOwnedProduct(sellerId, productId);
        product.updateStatus(status);
        return toResponse(products.save(product));
    }

    @Override
    @Transactional
    public ProductResponse updateChannelAvailability(UUID sellerId, UUID productId, ProductSalesChannel channel,
            boolean available) {
        if (sellerId == null || productId == null || channel == null)
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product channel availability update");
        Product product = requireOwnedProduct(sellerId, productId);
        product.updateChannelAvailability(channel, available);
        return toResponse(products.save(product));
    }


    @Override
    @Transactional
    public SellerSkuResponse registerSku(UUID sellerId, UUID productId, RegisterSellerSkuRequest request) {
        if (sellerId == null || productId == null || request == null || request.skuId() == null
                || request.skuId().isBlank() || request.skuId().length() > 128
                || request.priceWon() <= 0 || request.initialStock() < 0)
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid seller SKU configuration");
        Product product = products.findById(productId).filter(candidate -> candidate.getSellerId().equals(sellerId))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller product: " + productId));
        if (sellerSkus.findBySellerIdAndSkuId(sellerId, request.skuId()).isPresent()) {
            throw new CatalogException(Code.CONFLICT, "Seller SKU is already registered");
        }
        SellerSku sku = sellerSkus.saveAndFlush(new SellerSku(sellerId, productId, request.skuId(),
                request.priceWon(), clock.instant()));
        inventory.createInventoryPool(new CreateInventoryPoolRequest(sellerId, request.skuId(), request.initialStock()));
        return toResponse(product, sku, clock.instant());
    }

    @Override
    @Transactional
    public SellerSkuResponse updateSkuPrice(UUID sellerId, UUID productId, String skuId, long priceWon) {
        if (sellerId == null || productId == null || skuId == null || skuId.isBlank() || priceWon <= 0) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid seller SKU price update");
        }
        Product product = requireOwnedProduct(sellerId, productId);
        SellerSku sku = requireOwnedSku(sellerId, productId, skuId);
        if (sku.getSalePriceWon() != null && sku.getSalePriceWon() >= priceWon) {
            throw new CatalogException(Code.INVALID_REQUEST, "Regular price must exceed the sale price");
        }
        sku.updatePrice(priceWon);
        return toResponse(product, sellerSkus.save(sku), clock.instant());
    }

    @Override
    @Transactional
    public SellerSkuResponse setSkuSalePrice(UUID sellerId, UUID productId, String skuId, long salePriceWon,
            Instant startsAt, Instant endsAt) {
        if (sellerId == null || productId == null || skuId == null || skuId.isBlank() || salePriceWon <= 0
                || startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid SKU sale price window");
        }
        Product product = requireOwnedProduct(sellerId, productId);
        SellerSku sku = requireOwnedSku(sellerId, productId, skuId);
        if (salePriceWon >= sku.getPriceWon()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Sale price must be below regular price");
        }
        sku.setSalePrice(salePriceWon, startsAt, endsAt);
        return toResponse(product, sellerSkus.save(sku), clock.instant());
    }

    @Override
    @Transactional
    public SellerSkuResponse clearSkuSalePrice(UUID sellerId, UUID productId, String skuId) {
        if (sellerId == null || productId == null || skuId == null || skuId.isBlank()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid SKU sale price cancellation");
        }
        Product product = requireOwnedProduct(sellerId, productId);
        SellerSku sku = requireOwnedSku(sellerId, productId, skuId);
        sku.clearSalePrice();
        return toResponse(product, sellerSkus.save(sku), clock.instant());
    }

    /**
     * 목록 조회는 하나의 커넥션을 페이지 조립 전체에 재사용합니다.
     *
     * <p>{@code readOnly} 트랜잭션을 걸지 않으면 쿼리 4개(목록·SKU·채널·카테고리)가
     * 각각 커넥션을 빌리고 반납합니다. 실측(160 VU, 20초 창)에서 HTTP 요청 1건당
     * 커넥션 획득 횟수가 <b>3.09회(트랜잭션 있음) 대비 6.43회(없음)</b> 였습니다.
     * 획득 1회마다 풀 대기가 붙으므로 요청당 대기가 약 2배가 됩니다.
     *
     * <p>앞서 "유휴 커넥션 비율"을 근거로 이 트랜잭션을 제거한 적이 있는데,
     * 그 지표는 대리 지표였습니다. 유휴 비율은 62%에서 28%로 줄었지만 커넥션 획득
     * 횟수가 2배가 되어 실제 비용은 늘었습니다. 커넥션 비용은 점유율이 아니라
     * <b>획득 횟수 x 대기 시간</b>으로 판단해야 합니다.
     *
     * <p>쿼리 사이에 앱 매핑이 끼어 커넥션이 유휴로 보이지만, 그 유휴를 없애려고
     * 트랜잭션을 빼면 획득 횟수가 늘어 손해입니다. 한 번 빌려 여러 쿼리에 쓰는 편이
     * 여러 번 빌리는 것보다 쌉니다.
     */
    @Override
    @Transactional(readOnly = true)
    public ProductListingPageResponse listProductsPage(UUID sellerId, UUID categoryId, String query,
            Integer requestedLimit, String cursor) {
        return listProductsPage(sellerId, categoryId, query, null, null, requestedLimit, cursor);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductListingPageResponse listProductsPage(UUID sellerId, UUID categoryId, String query,
            Long minPriceWon, Long maxPriceWon, Integer requestedLimit, String cursor) {
        return listProductsPageInternal(
                sellerId, categoryId, query, minPriceWon, maxPriceWon, requestedLimit, cursor, false);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductListingPageResponse listSellerProductsPage(UUID sellerId, UUID categoryId, String query,
            Integer requestedLimit, String cursor) {
        if (sellerId == null) throw new CatalogException(Code.INVALID_REQUEST, "Seller ID required");
        return listProductsPageInternal(sellerId, categoryId, query, null, null, requestedLimit, cursor, true);
    }

    private ProductListingPageResponse listProductsPageInternal(
            UUID sellerId, UUID categoryId, String query, Long minPriceWon, Long maxPriceWon,
            Integer requestedLimit, String cursor, boolean managementScope) {
        ListingCursor position = decodeCursor(cursor);
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new CatalogException(Code.INVALID_REQUEST, "Product page limit is out of range");
        }
        if (minPriceWon != null && minPriceWon < 0 || maxPriceWon != null && maxPriceWon < 0
                || minPriceWon != null && maxPriceWon != null && minPriceWon > maxPriceWon) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product price range");
        }
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (categoryId != null && sellerId == null) {
            throw new CatalogException(Code.INVALID_REQUEST, "Category filter requires seller scope");
        }
        if (position != null && position.version() < 4 && position.categoryFiltered()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Legacy category cursor is not supported");
        }
        if (position != null && (!Objects.equals(position.filterSellerId(), sellerId)
                || position.version() >= 3 && position.managementScope() != managementScope
                || !position.keyword().equals(keyword) || !Objects.equals(position.categoryId(), categoryId)
                || position.categoryFiltered() != (categoryId != null)
                || !Objects.equals(position.minPriceWon(), minPriceWon)
                || !Objects.equals(position.maxPriceWon(), maxPriceWon))) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product cursor");
        }
        if (sellerId != null && !sellers.existsById(sellerId)) {
            throw new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId);
        }
        if (categoryId != null) requireOwnedCategory(sellerId, categoryId);
        UUID cursorSeller = position == null ? null : position.positionSellerId();
        UUID cursorProduct = position == null ? null : position.productId();
        if (sellerId != null && cursorSeller != null && !sellerId.equals(cursorSeller)) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product cursor");
        }
        List<Product> fetched = managementScope
                ? products.findListingPageByScope(sellerId, categoryId, keyword,
                        cursorSeller, cursorProduct, limit + 1, minPriceWon, maxPriceWon, asOf, true)
                : products.findListingPage(sellerId, categoryId, keyword, cursorSeller, cursorProduct, limit + 1,
                        minPriceWon, maxPriceWon, asOf);
        boolean hasMore = fetched.size() > limit;
        List<Product> page = fetched.stream().limit(limit).toList();
        List<UUID> productIds = page.stream().map(Product::getProductId).toList();
        if (productIds.isEmpty()) return new ProductListingPageResponse(List.of(), null);
        Map<UUID, Product> productsById = page.stream().collect(Collectors.toMap(Product::getProductId, product -> product));
        Map<UUID, List<ProductSalesChannel>> availableChannelsByProductId =
                products.findAvailableChannelsByProductIdIn(productIds).stream().collect(Collectors.groupingBy(
                        ProductChannelAvailability::getProductId,
                        Collectors.mapping(row -> ProductSalesChannel.valueOf(row.getSalesChannel()),
                                Collectors.toList())));
        Map<UUID, List<UUID>> categoryIdsByProductId = categoryAssignments
                .findAllByProductIdInOrderByProductIdAscCategoryIdAsc(productIds).stream()
                .collect(Collectors.groupingBy(ProductCategoryAssignment::getProductId,
                        Collectors.mapping(ProductCategoryAssignment::getCategoryId, Collectors.toList())));
        Instant pricingAt = asOf;
        Map<UUID, List<SellerSkuResponse>> variantsByProductId = sellerSkus.findAllByProductIdIn(productIds).stream()
                .map(sku -> toResponse(productsById.get(sku.getProductId()), sku, pricingAt))
                .collect(Collectors.groupingBy(SellerSkuResponse::productId));
        List<ProductListingResponse> items = page.stream().map(product -> new ProductListingResponse(
                toResponse(product, categoryIdsByProductId.getOrDefault(product.getProductId(), List.of()),
                        availableChannelsByProductId.getOrDefault(product.getProductId(), List.of())),
                variantsByProductId.getOrDefault(product.getProductId(), List.of()).stream()
                        .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList())).toList();
        String nextCursor = null;
        if (hasMore) {
            Product last = page.getLast();
            nextCursor = encodeCursor(sellerId, categoryId, keyword, minPriceWon, maxPriceWon,
                    last.getSellerId(), last.getProductId(), managementScope);
        }
        return new ProductListingPageResponse(items, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPublicOrderable(UUID productId) {
        if (productId == null) throw new CatalogException(Code.INVALID_REQUEST, "Product ID required");
        return products.existsPublicOrderableByProductId(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductListingResponse findProduct(UUID productId) {
        if (productId == null) throw new CatalogException(Code.INVALID_REQUEST, "Product ID required");
        Product product = products.findPublicOrderableByProductId(productId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown product: " + productId));
        return toListing(product);
    }


    private Product requireOwnedProduct(UUID sellerId, UUID productId) {
        return products.findByProductIdAndSellerIdForUpdate(productId, sellerId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller product: " + productId));
    }
    private SellerSku requireOwnedSku(UUID sellerId, UUID productId, String skuId) {
        return sellerSkus.findBySellerIdAndSkuId(sellerId, skuId)
                .filter(candidate -> candidate.getProductId().equals(productId))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller SKU: " + skuId));
    }
    private ProductCategory requireOwnedCategory(UUID sellerId, UUID categoryId) {
        return categories.findByCategoryIdAndSellerId(categoryId, sellerId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller category: " + categoryId));
    }

    private List<UUID> resolveCategoryIds(UUID sellerId, List<UUID> requestedCategoryIds, boolean defaultWhenEmpty) {
        if (requestedCategoryIds == null || requestedCategoryIds.isEmpty()) {
            if (!defaultWhenEmpty) {
                throw new CatalogException(Code.INVALID_REQUEST, "At least one product category is required");
            }
            UUID defaultCategoryId = categories.findBySellerIdAndCode(sellerId, ProductCategory.UNCATEGORIZED_CODE)
                    .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Seller default category is missing"))
                    .getCategoryId();
            return List.of(defaultCategoryId);
        }
        if (requestedCategoryIds.stream().anyMatch(Objects::isNull)
                || requestedCategoryIds.stream().distinct().count() != requestedCategoryIds.size()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product category assignment");
        }
        List<ProductCategory> ownedCategories = categories
                .findAllBySellerIdAndCategoryIdInOrderByCategoryIdAsc(sellerId, requestedCategoryIds);
        if (ownedCategories.size() != requestedCategoryIds.size()) {
            throw new CatalogException(Code.NOT_FOUND, "Unknown seller category assignment");
        }
        boolean includesUncategorized = ownedCategories.stream()
                .anyMatch(category -> ProductCategory.UNCATEGORIZED_CODE.equals(category.getCode()));
        if (includesUncategorized && ownedCategories.size() > 1) {
            throw new CatalogException(Code.INVALID_REQUEST, "Uncategorized cannot be combined with other categories");
        }
        return ownedCategories.stream().map(ProductCategory::getCategoryId).sorted().toList();
    }

    private static List<ProductCategoryAssignment> toAssignments(UUID sellerId, UUID productId,
            List<UUID> categoryIds) {
        return categoryIds.stream().map(categoryId ->
                new ProductCategoryAssignment(sellerId, productId, categoryId)).toList();
    }

    private ProductListingResponse toListing(Product product) {
        Instant pricingAt = clock.instant();
        List<SellerSkuResponse> variants = sellerSkus.findAllByProductId(product.getProductId()).stream()
                .map(sku -> toResponse(product, sku, pricingAt))
                .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList();
        return new ProductListingResponse(toResponse(product), variants);
    }

    private ProductResponse toResponse(Product product) {
        List<UUID> categoryIds = categoryAssignments
                .findAllBySellerIdAndProductIdOrderByCategoryIdAsc(product.getSellerId(), product.getProductId())
                .stream().map(ProductCategoryAssignment::getCategoryId).toList();
        return toResponse(product, categoryIds, product.getChannelAvailability().entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().toList());
    }

    private static ProductResponse toResponse(Product product, List<UUID> categoryIds,
            List<ProductSalesChannel> availableChannels) {
        List<UUID> allCategoryIds = new ArrayList<>(categoryIds);
        allCategoryIds.add(product.getPrimaryCategoryId());
        return new ProductResponse(product.getProductId(), product.getSellerId(), product.getName(),
                product.getDescription(), allCategoryIds, product.getStatus(), availableChannels);
    }

    private static SellerSkuResponse toResponse(Product product, SellerSku sku, Instant asOf) {
        return new SellerSkuResponse(sku.getSellerId(), sku.getProductId(), sku.getSkuId(),
                product.getName(), sku.getPriceWon(), sku.getSalePriceWon(), sku.getSaleStartsAt(),
                sku.getSaleEndsAt(), sku.effectivePriceAt(asOf));

    }
    private static ListingCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", -1);
            if (parts.length == 5 && "v1".equals(parts[0])) {
                UUID filterSellerId = "-".equals(parts[1]) ? null : UUID.fromString(parts[1]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                return new ListingCursor(1, false, filterSellerId, null, keyword, null, null,
                        UUID.fromString(parts[3]), UUID.fromString(parts[4]));
            }
            if (parts.length == 6 && "v2".equals(parts[0])) {
                UUID filterSellerId = UUID.fromString(parts[1]);
                UUID categoryId = "-".equals(parts[2]) ? null : UUID.fromString(parts[2]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
                return new ListingCursor(2, false, filterSellerId, categoryId, keyword, null, null,
                        UUID.fromString(parts[4]), UUID.fromString(parts[5]));
            }
            if (parts.length == 7 && ("v3".equals(parts[0]) || "v4".equals(parts[0]))) {
                boolean managementScope = decodeManagementScope(parts[1]);
                UUID filterSellerId = "-".equals(parts[2]) ? null : UUID.fromString(parts[2]);
                UUID categoryId = "-".equals(parts[3]) ? null : UUID.fromString(parts[3]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
                return new ListingCursor("v4".equals(parts[0]) ? 4 : 3, managementScope, filterSellerId,
                        categoryId, keyword, null, null, UUID.fromString(parts[5]), UUID.fromString(parts[6]));
            }
            if (parts.length == 9 && "v5".equals(parts[0])) {
                boolean managementScope = decodeManagementScope(parts[1]);
                UUID filterSellerId = "-".equals(parts[2]) ? null : UUID.fromString(parts[2]);
                UUID categoryId = "-".equals(parts[3]) ? null : UUID.fromString(parts[3]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
                return new ListingCursor(5, managementScope, filterSellerId, categoryId, keyword,
                        decodePriceBound(parts[5]), decodePriceBound(parts[6]),
                        UUID.fromString(parts[7]), UUID.fromString(parts[8]));
            }
            throw new IllegalArgumentException("Malformed cursor");
        } catch (IllegalArgumentException exception) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product cursor");
        }
    }

    private static boolean decodeManagementScope(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Malformed cursor scope");
        };
    }

    private static Long decodePriceBound(String value) {
        if ("-".equals(value)) return null;
        long price = Long.parseLong(value);
        if (price < 0) throw new IllegalArgumentException("Malformed cursor price");
        return price;
    }

    private static String encodeCursor(UUID filterSellerId, UUID categoryId, String keyword,
            Long minPriceWon, Long maxPriceWon, UUID positionSellerId, UUID productId, boolean managementScope) {
        String encodedKeyword = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keyword.getBytes(StandardCharsets.UTF_8));
        String value = "v5:" + managementScope + ":" + (filterSellerId == null ? "-" : filterSellerId) + ":"
                + (categoryId == null ? "-" : categoryId) + ":" + encodedKeyword + ":"
                + (minPriceWon == null ? "-" : minPriceWon) + ":" + (maxPriceWon == null ? "-" : maxPriceWon) + ":"
                + positionSellerId + ":" + productId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record ListingCursor(int version, boolean managementScope, UUID filterSellerId, UUID categoryId,
            String keyword, Long minPriceWon, Long maxPriceWon, UUID positionSellerId, UUID productId) {
        boolean categoryFiltered() { return categoryId != null; }
    }
}