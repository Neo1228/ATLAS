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
import com.atlas.catalog.persistence.entity.ProductCategory;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.persistence.projection.ProductChannelAvailability;
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductService;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.catalog.service.ProductStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
        UUID categoryId;
        if (request.categoryId() == null) {
            categoryId = categories.findBySellerIdAndCode(sellerId, ProductCategory.UNCATEGORIZED_CODE)
                    .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Seller default category is missing"))
                    .getCategoryId();
        } else {
            categoryId = requireOwnedCategory(sellerId, request.categoryId()).getCategoryId();
        }
        Product product = products.save(new Product(sellerId, categoryId, request.name(), request.description()));
        return toResponse(product);
    }

    @Override
    @Transactional
    public ProductResponse updateProductCategory(UUID sellerId, UUID productId, UUID categoryId) {
        if (sellerId == null || productId == null || categoryId == null) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product category assignment");
        }
        Product product = products.findById(productId).filter(candidate -> candidate.getSellerId().equals(sellerId))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller product: " + productId));
        requireOwnedCategory(sellerId, categoryId);
        product.assignCategory(categoryId);
        return toResponse(products.save(product));
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

    @Override
    @Transactional(readOnly = true)
    public ProductListingPageResponse listProductsPage(UUID sellerId, UUID categoryId, String query,
            Integer requestedLimit, String cursor) {
        return listProductsPageInternal(sellerId, categoryId, query, requestedLimit, cursor, false);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductListingPageResponse listSellerProductsPage(UUID sellerId, UUID categoryId, String query,
            Integer requestedLimit, String cursor) {
        if (sellerId == null) throw new CatalogException(Code.INVALID_REQUEST, "Seller ID required");
        return listProductsPageInternal(sellerId, categoryId, query, requestedLimit, cursor, true);
    }
    private ProductListingPageResponse listProductsPageInternal(UUID sellerId, UUID categoryId, String query,
            Integer requestedLimit, String cursor, boolean managementScope) {
        ListingCursor position = decodeCursor(cursor);
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new CatalogException(Code.INVALID_REQUEST, "Product page limit is out of range");
        }
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
                || position.categoryFiltered() != (categoryId != null))) {
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
                        cursorSeller, cursorProduct, limit + 1, true)
                : products.findListingPage(sellerId, categoryId, keyword, cursorSeller, cursorProduct, limit + 1);
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
        Instant pricingAt = clock.instant();
        Map<UUID, List<SellerSkuResponse>> variantsByProductId = sellerSkus.findAllByProductIdIn(productIds).stream()
                .map(sku -> toResponse(productsById.get(sku.getProductId()), sku, pricingAt))
                .collect(Collectors.groupingBy(SellerSkuResponse::productId));
        List<ProductListingResponse> items = page.stream().map(product -> new ProductListingResponse(
                toResponse(product, availableChannelsByProductId.getOrDefault(product.getProductId(), List.of())),
                variantsByProductId.getOrDefault(product.getProductId(), List.of()).stream()
                        .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList())).toList();
        String nextCursor = null;
        if (hasMore) {
            Product last = page.getLast();
            nextCursor = encodeCursor(sellerId, categoryId, keyword, last.getSellerId(), last.getProductId(),
                    managementScope);
        }
        return new ProductListingPageResponse(items, nextCursor);
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
        return products.findById(productId).filter(candidate -> candidate.getSellerId().equals(sellerId))
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

    private ProductListingResponse toListing(Product product) {
        Instant pricingAt = clock.instant();
        List<SellerSkuResponse> variants = sellerSkus.findAllByProductId(product.getProductId()).stream()
                .map(sku -> toResponse(product, sku, pricingAt))
                .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList();
        return new ProductListingResponse(toResponse(product), variants);
    }

    private static ProductResponse toResponse(Product product) {
        return toResponse(product, product.getChannelAvailability().entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().toList());
    }

    private static ProductResponse toResponse(Product product, List<ProductSalesChannel> availableChannels) {
        return new ProductResponse(product.getProductId(), product.getSellerId(), product.getName(),
                product.getDescription(), product.getCategoryId(), product.getStatus(), availableChannels);
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
                return new ListingCursor(1, false, filterSellerId, null, keyword,
                        UUID.fromString(parts[3]), UUID.fromString(parts[4]));
            }
            if (parts.length == 6 && "v2".equals(parts[0])) {
                UUID filterSellerId = UUID.fromString(parts[1]);
                UUID categoryId = "-".equals(parts[2]) ? null : UUID.fromString(parts[2]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
                return new ListingCursor(2, false, filterSellerId, categoryId, keyword,
                        UUID.fromString(parts[4]), UUID.fromString(parts[5]));
            }
            if (parts.length == 7 && ("v3".equals(parts[0]) || "v4".equals(parts[0]))) {
                boolean managementScope = switch (parts[1]) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw new IllegalArgumentException("Malformed cursor scope");
                };
                UUID filterSellerId = "-".equals(parts[2]) ? null : UUID.fromString(parts[2]);
                UUID categoryId = "-".equals(parts[3]) ? null : UUID.fromString(parts[3]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
                return new ListingCursor("v4".equals(parts[0]) ? 4 : 3, managementScope, filterSellerId, categoryId, keyword,
                        UUID.fromString(parts[5]), UUID.fromString(parts[6]));
            }
            throw new IllegalArgumentException("Malformed cursor");
        } catch (IllegalArgumentException exception) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product cursor");
        }
    }

    private static String encodeCursor(UUID filterSellerId, UUID categoryId, String keyword,
            UUID positionSellerId, UUID productId, boolean managementScope) {
        String encodedKeyword = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keyword.getBytes(StandardCharsets.UTF_8));
        String value = "v4:" + managementScope + ":" + (filterSellerId == null ? "-" : filterSellerId) + ":"
                + (categoryId == null ? "-" : categoryId) + ":" + encodedKeyword + ":"
                + positionSellerId + ":" + productId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record ListingCursor(int version, boolean managementScope, UUID filterSellerId, UUID categoryId,
            String keyword, UUID positionSellerId, UUID productId) {
        boolean categoryFiltered() { return categoryId != null; }
    }
}
