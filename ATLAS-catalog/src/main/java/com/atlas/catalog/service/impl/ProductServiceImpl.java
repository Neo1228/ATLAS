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
import com.atlas.catalog.repository.ProductCategoryRepository;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductService;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
        return toResponse(product, sku);
    }

    @Override
    @Transactional
    public SellerSkuResponse updateSkuPrice(UUID sellerId, UUID productId, String skuId, long priceWon) {
        if (sellerId == null || productId == null || skuId == null || skuId.isBlank() || priceWon <= 0) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid seller SKU price update");
        }
        Product product = products.findById(productId).filter(candidate -> candidate.getSellerId().equals(sellerId))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller product: " + productId));
        SellerSku sku = sellerSkus.findBySellerIdAndSkuId(sellerId, skuId)
                .filter(candidate -> candidate.getProductId().equals(productId))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller SKU: " + skuId));
        sku.updatePrice(priceWon);
        return toResponse(product, sellerSkus.save(sku));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductListingPageResponse listProductsPage(UUID sellerId, UUID categoryId, String query,
            Integer requestedLimit, String cursor) {
        ListingCursor position = decodeCursor(cursor);
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new CatalogException(Code.INVALID_REQUEST, "Product page limit is out of range");
        }
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (categoryId != null && sellerId == null) {
            throw new CatalogException(Code.INVALID_REQUEST, "Category filter requires seller scope");
        }
        if (position != null && (!Objects.equals(position.filterSellerId(), sellerId)
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
        List<Product> fetched = products.findListingPage(sellerId, categoryId, keyword,
                cursorSeller, cursorProduct, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<Product> page = fetched.stream().limit(limit).toList();
        List<UUID> productIds = page.stream().map(Product::getProductId).toList();
        if (productIds.isEmpty()) return new ProductListingPageResponse(List.of(), null);
        Map<UUID, Product> productsById = page.stream().collect(Collectors.toMap(Product::getProductId, product -> product));
        Map<UUID, List<SellerSkuResponse>> variantsByProductId = sellerSkus.findAllByProductIdIn(productIds).stream()
                .map(sku -> toResponse(productsById.get(sku.getProductId()), sku))
                .collect(Collectors.groupingBy(SellerSkuResponse::productId));
        List<ProductListingResponse> items = page.stream().map(product -> new ProductListingResponse(toResponse(product),
                variantsByProductId.getOrDefault(product.getProductId(), List.of()).stream()
                        .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList())).toList();
        String nextCursor = null;
        if (hasMore) {
            Product last = page.getLast();
            nextCursor = encodeCursor(sellerId, categoryId, keyword, last.getSellerId(), last.getProductId());
        }
        return new ProductListingPageResponse(items, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductListingResponse findProduct(UUID productId) {
        if (productId == null) throw new CatalogException(Code.INVALID_REQUEST, "Product ID required");
        Product product = products.findById(productId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown product: " + productId));
        return toListing(product);
    }

    private ProductCategory requireOwnedCategory(UUID sellerId, UUID categoryId) {
        return categories.findByCategoryIdAndSellerId(categoryId, sellerId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller category: " + categoryId));
    }

    private ProductListingResponse toListing(Product product) {
        List<SellerSkuResponse> variants = sellerSkus.findAllByProductId(product.getProductId()).stream()
                .map(sku -> toResponse(product, sku)).sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList();
        return new ProductListingResponse(toResponse(product), variants);
    }

    private static ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getProductId(), product.getSellerId(), product.getName(),
                product.getDescription(), product.getCategoryId());
    }

    private static SellerSkuResponse toResponse(Product product, SellerSku sku) {
        return new SellerSkuResponse(sku.getSellerId(), sku.getProductId(), sku.getSkuId(),
                product.getName(), sku.getPriceWon());
    }

    private static ListingCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", -1);
            if (parts.length == 5 && "v1".equals(parts[0])) {
                UUID filterSellerId = "-".equals(parts[1]) ? null : UUID.fromString(parts[1]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                return new ListingCursor(1, filterSellerId, null, keyword, UUID.fromString(parts[3]),
                        UUID.fromString(parts[4]));
            }
            if (parts.length == 6 && "v2".equals(parts[0])) {
                UUID filterSellerId = UUID.fromString(parts[1]);
                UUID categoryId = UUID.fromString(parts[2]);
                String keyword = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
                return new ListingCursor(2, filterSellerId, categoryId, keyword, UUID.fromString(parts[4]),
                        UUID.fromString(parts[5]));
            }
            throw new IllegalArgumentException("Malformed cursor");
        } catch (IllegalArgumentException exception) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product cursor");
        }
    }

    private static String encodeCursor(UUID filterSellerId, UUID categoryId, String keyword,
            UUID positionSellerId, UUID productId) {
        String encodedKeyword = Base64.getUrlEncoder().withoutPadding().encodeToString(keyword.getBytes(StandardCharsets.UTF_8));
        String value = categoryId == null
                ? "v1:" + (filterSellerId == null ? "-" : filterSellerId) + ":" + encodedKeyword + ":" + positionSellerId + ":" + productId
                : "v2:" + filterSellerId + ":" + categoryId + ":" + encodedKeyword + ":" + positionSellerId + ":" + productId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record ListingCursor(int version, UUID filterSellerId, UUID categoryId, String keyword,
            UUID positionSellerId, UUID productId) {
        boolean categoryFiltered() { return version == 2; }
    }
}
