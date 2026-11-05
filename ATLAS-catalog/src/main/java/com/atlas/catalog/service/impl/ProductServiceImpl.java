package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final SellerRepository sellers;
    private final ProductRepository products;
    private final SellerSkuRepository sellerSkus;
    private final SellerSkuService sellerSkuService;
    private final InventoryService inventory;
    private final Clock clock;

    @Override
    @Transactional
    public ProductResponse createProduct(UUID sellerId, CreateProductRequest request) {
        if (sellerId == null || request == null || request.name() == null || request.name().isBlank()) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product configuration");
        }
        if (!sellers.existsById(sellerId)) throw new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId);
        Product product = products.save(new Product(sellerId, request.name(), request.description()));
        return toResponse(product);
    }

    @Override
    @Transactional
    public SellerSkuResponse registerSku(UUID sellerId, UUID productId, RegisterSellerSkuRequest request) {
        if (sellerId == null || productId == null || request == null || request.skuId() == null
                || request.skuId().isBlank() || request.skuId().length() > 128
                || request.priceWon() <= 0 || request.initialStock() < 0) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid seller SKU configuration");
        }
        Product product = products.findById(productId).filter(candidate -> candidate.getSellerId().equals(sellerId))
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown seller product: " + productId));
        if (sellerSkus.findBySellerIdAndSkuId(sellerId, request.skuId()).isPresent()) {
            throw new CatalogException(Code.CONFLICT, "Seller SKU is already registered");
        }
        SellerSku sku = sellerSkus.save(new SellerSku(sellerId, productId, request.skuId(),
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
    public List<ProductListingResponse> listProducts() {
        Map<UUID, List<Product>> productsBySeller = products.findAll().stream()
                .collect(Collectors.groupingBy(Product::getSellerId));
        return productsBySeller.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> toListings(entry.getKey(), entry.getValue()).stream()).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductListingResponse> listProducts(UUID sellerId) {
        if (sellerId == null) throw new CatalogException(Code.INVALID_REQUEST, "Seller ID is required");
        if (!sellers.existsById(sellerId)) throw new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId);
        return toListings(sellerId, products.findAllBySellerId(sellerId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductListingResponse> listProductsPage(UUID sellerId, String keyword, UUID cursorSeller,
            UUID cursorProduct, int limit) {
        if (sellerId != null && !sellers.existsById(sellerId)) {
            throw new CatalogException(Code.NOT_FOUND, "Unknown seller: " + sellerId);
        }
        if (sellerId != null && cursorSeller != null && !sellerId.equals(cursorSeller)) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product cursor");
        }
        if ((cursorSeller == null) != (cursorProduct == null)) {
            throw new CatalogException(Code.INVALID_REQUEST, "Invalid product cursor");
        }
        List<Product> page = products.findListingPage(sellerId, keyword, cursorSeller, cursorProduct, limit + 1);
        List<UUID> productIds = page.stream().limit(limit).map(Product::getProductId).toList();
        if (productIds.isEmpty()) return List.of();
        Map<UUID, Product> productsById = page.stream()
                .collect(Collectors.toMap(Product::getProductId, product -> product));
        Map<UUID, List<SellerSkuResponse>> variantsByProductId = sellerSkus.findAllByProductIdIn(productIds).stream()
                .map(sku -> toResponse(productsById.get(sku.getProductId()), sku))
                .collect(Collectors.groupingBy(SellerSkuResponse::productId));
        return page.stream().map(product -> new ProductListingResponse(toResponse(product),
                variantsByProductId.getOrDefault(product.getProductId(), List.of()).stream()
                        .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductListingResponse findProduct(UUID productId) {
        if (productId == null) throw new CatalogException(Code.INVALID_REQUEST, "Product ID is required");
        Product product = products.findById(productId)
                .orElseThrow(() -> new CatalogException(Code.NOT_FOUND, "Unknown product: " + productId));
        return toListing(product);
    }

    private List<ProductListingResponse> toListings(UUID sellerId, List<Product> sellerProducts) {
        List<String> skuIds = sellerSkus.findAllBySellerId(sellerId).stream()
                .map(SellerSku::getSkuId).toList();
        List<SellerSkuResponse> variants = sellerSkuService.findAll(sellerId, skuIds);
        Map<UUID, List<SellerSkuResponse>> variantsByProductId = variants.stream()
                .collect(Collectors.groupingBy(SellerSkuResponse::productId));
        return sellerProducts.stream()
                .map(product -> new ProductListingResponse(toResponse(product),
                        variantsByProductId.getOrDefault(product.getProductId(), List.of()).stream()
                                .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList()))
                .sorted(Comparator.comparing(listing -> listing.product().productId()))
                .toList();
    }

    private ProductListingResponse toListing(Product product) {
        List<SellerSkuResponse> variants = sellerSkus.findAllByProductId(product.getProductId()).stream()
                .map(sku -> toResponse(product, sku))
                .sorted(Comparator.comparing(SellerSkuResponse::skuId)).toList();
        return new ProductListingResponse(toResponse(product), variants);
    }

    private static ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getProductId(), product.getSellerId(), product.getName(),
                product.getDescription());
    }

    private static SellerSkuResponse toResponse(Product product, SellerSku sku) {
        return new SellerSkuResponse(sku.getSellerId(), sku.getProductId(), sku.getSkuId(),
                product.getName(), sku.getPriceWon());
    }
}
