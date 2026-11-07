package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerSkuServiceImpl implements SellerSkuService {
    private final SellerSkuRepository sellerSkus;
    private final ProductRepository products;

    @Override
    @Transactional(readOnly = true)
    public SellerSkuResponse find(UUID sellerId, String skuId) {
        if (sellerId == null || skuId == null) return null;
        SellerSku sku = sellerSkus.findBySellerIdAndSkuId(sellerId, skuId).orElse(null);
        if (sku == null) return null;
        Product product = products.findById(sku.getProductId()).orElse(null);
        return product == null ? null : toResponse(product, sku);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SellerSkuResponse> findAll(UUID sellerId, List<String> skuIds) {
        return findAllResponses(sellerId, skuIds);
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = CatalogException.class)
    public SellerSkuResponse findOrderableSellerSku(UUID sellerId, String skuId, ProductSalesChannel channel) {
        if (sellerId == null || skuId == null || channel == null) return null;
        List<SellerSkuResponse> matches = findOrderableSellerSkus(sellerId, List.of(skuId), channel);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = CatalogException.class)
    public List<SellerSkuResponse> findOrderableSellerSkus(UUID sellerId, List<String> skuIds,
            ProductSalesChannel channel) {
        if (sellerId == null || skuIds == null || skuIds.isEmpty() || channel == null) return List.of();
        List<String> ids = normalize(skuIds);
        if (ids.isEmpty()) return List.of();
        List<SellerSku> skus = sellerSkus.findAllBySellerIdAndSkuIdIn(sellerId, ids);
        if (skus.isEmpty()) return List.of();
        List<UUID> productIds = skus.stream().map(SellerSku::getProductId).distinct().toList();
        Map<UUID, Product> productById = products.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity()));
        List<UUID> orderableIds = products.findOrderableProductIds(productIds, channel.name());
        for (SellerSku sku : skus) {
            Product product = productById.get(sku.getProductId());
            if (product != null && !orderableIds.contains(product.getProductId())) {
                throw new CatalogException(Code.PRODUCT_NOT_ORDERABLE,
                        "Product is not orderable: " + product.getProductId());
            }
        }
        return skus.stream().map(sku -> {
            Product product = productById.get(sku.getProductId());
            return product == null ? null : toResponse(product, sku);
        }).filter(Objects::nonNull).toList();
    }

    private List<SellerSkuResponse> findAllResponses(UUID sellerId, List<String> skuIds) {
        if (sellerId == null || skuIds == null || skuIds.isEmpty()) return List.of();
        List<String> ids = normalize(skuIds);
        if (ids.isEmpty()) return List.of();
        List<SellerSku> skus = sellerSkus.findAllBySellerIdAndSkuIdIn(sellerId, ids);
        if (skus.isEmpty()) return List.of();
        List<UUID> productIds = skus.stream().map(SellerSku::getProductId).distinct().toList();
        Map<UUID, Product> productById = products.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity()));
        return skus.stream().map(sku -> {
            Product product = productById.get(sku.getProductId());
            return product == null ? null : toResponse(product, sku);
        }).filter(Objects::nonNull).toList();
    }

    private static List<String> normalize(List<String> skuIds) {
        return skuIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    private static SellerSkuResponse toResponse(Product product, SellerSku sku) {
        return new SellerSkuResponse(sku.getSellerId(), sku.getProductId(), sku.getSkuId(),
                product.getName(), sku.getPriceWon());
    }
}
