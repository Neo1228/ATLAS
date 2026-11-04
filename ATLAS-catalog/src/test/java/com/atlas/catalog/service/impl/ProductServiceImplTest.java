package com.atlas.catalog.service.impl;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingResponse;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.repository.SellerRepository;
import com.atlas.catalog.repository.SellerSkuRepository;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.inventory.dto.request.CreateInventoryPoolRequest;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceImplTest {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SellerSkuRepository sellerSkus = mock(SellerSkuRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ProductServiceImpl service = new ProductServiceImpl(sellers, products, sellerSkus,
            new SellerSkuServiceImpl(sellerSkus, products), inventory, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID sellerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void createsProductForExistingSeller() {
        when(sellers.existsById(sellerId)).thenReturn(true);
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.createProduct(sellerId, new CreateProductRequest("Tea", "Green tea"));

        assertEquals(sellerId, response.sellerId());
        assertEquals("Tea", response.name());
        verify(products).save(any(Product.class));
    }

    @Test
    void registersSkuAndInitializesInventoryPool() {
        Product product = new Product(sellerId, "Tea", null);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "TEA-1")).thenReturn(Optional.empty());
        when(sellerSkus.save(any(SellerSku.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.registerSku(sellerId, productId,
                new RegisterSellerSkuRequest("TEA-1", 1200, 0));

        assertEquals("Tea", response.productName());
        assertEquals(1200, response.priceWon());
        ArgumentCaptor<SellerSku> savedSku = ArgumentCaptor.forClass(SellerSku.class);
        verify(sellerSkus).save(savedSku.capture());
        assertEquals(now, savedSku.getValue().getCreatedAt());
        verify(inventory).createInventoryPool(new CreateInventoryPoolRequest(sellerId, "TEA-1", 0));
    }

    @Test
    void updatesOwnedSkuPriceAndReturnsUpdatedResponse() {
        Product product = new Product(sellerId, "Tea", null);
        SellerSku sku = new SellerSku(sellerId, productId, "TEA-1", 1200, now);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(sellerSkus.findBySellerIdAndSkuId(sellerId, "TEA-1")).thenReturn(Optional.of(sku));
        when(sellerSkus.save(sku)).thenReturn(sku);

        var response = service.updateSkuPrice(sellerId, productId, "TEA-1", 1500);

        assertEquals(1500, response.priceWon());
        assertEquals("Tea", response.productName());
        verify(sellerSkus).save(sku);
    }

    @Test
    void rejectsUnknownSeller() {
        when(sellers.existsById(sellerId)).thenReturn(false);

        CatalogException error = assertThrows(CatalogException.class,
                () -> service.createProduct(sellerId, new CreateProductRequest("Tea", null)));

        assertEquals(Code.NOT_FOUND, error.code());
    }

    @Test
    void rejectsInvalidSkuAndNonPositivePrice() {
        CatalogException invalidSku = assertThrows(CatalogException.class,
                () -> service.registerSku(sellerId, productId, new RegisterSellerSkuRequest(" ", 1200, 0)));
        CatalogException invalidPrice = assertThrows(CatalogException.class,
                () -> service.registerSku(sellerId, productId, new RegisterSellerSkuRequest("TEA-1", 0, 0)));

        assertEquals(Code.INVALID_REQUEST, invalidSku.code());
        assertEquals(Code.INVALID_REQUEST, invalidPrice.code());
        verify(products, never()).findById(productId);
        verify(inventory, never()).createInventoryPool(any());
    }

    @Test
    void listsProductsInStableSellerAndProductOrder() {
        UUID firstSellerId = new UUID(0, 1);
        UUID secondSellerId = new UUID(0, 2);
        UUID firstProductId = new UUID(0, 11);
        UUID secondProductId = new UUID(0, 12);
        UUID thirdProductId = new UUID(0, 13);
        Product firstProduct = product(firstSellerId, firstProductId, "First");
        Product secondProduct = product(firstSellerId, secondProductId, "Second");
        Product thirdProduct = product(secondSellerId, thirdProductId, "Third");
        when(products.findAll()).thenReturn(List.of(thirdProduct, secondProduct, firstProduct));
        when(products.findAllBySellerId(firstSellerId)).thenReturn(List.of(secondProduct, firstProduct));
        when(sellers.existsById(firstSellerId)).thenReturn(true);
        when(sellerSkus.findAllBySellerId(any(UUID.class))).thenReturn(List.of());
        ProductServiceImpl listingService = new ProductServiceImpl(sellers, products, sellerSkus,
                mock(SellerSkuService.class), inventory, Clock.fixed(now, ZoneOffset.UTC));

        List<ProductListingResponse> allProducts = listingService.listProducts();
        List<ProductListingResponse> sellerProducts = listingService.listProducts(firstSellerId);

        assertEquals(List.of(firstProductId, secondProductId, thirdProductId),
                allProducts.stream().map(listing -> listing.product().productId()).toList());
        assertEquals(List.of(firstSellerId, firstSellerId, secondSellerId),
                allProducts.stream().map(listing -> listing.product().sellerId()).toList());
        assertEquals(List.of(firstProductId, secondProductId),
                sellerProducts.stream().map(listing -> listing.product().productId()).toList());
    }

    @Test
    void sortsVariantsBySkuIdInProductListings() {
        UUID listingSellerId = UUID.randomUUID();
        UUID listingProductId = UUID.randomUUID();
        Product product = product(listingSellerId, listingProductId, "Tea");
        SellerSku laterSku = mock(SellerSku.class);
        when(laterSku.getSellerId()).thenReturn(listingSellerId);
        when(laterSku.getProductId()).thenReturn(listingProductId);
        when(laterSku.getSkuId()).thenReturn("SKU-Z");
        when(laterSku.getPriceWon()).thenReturn(1500L);
        SellerSku earlierSku = mock(SellerSku.class);
        when(earlierSku.getSellerId()).thenReturn(listingSellerId);
        when(earlierSku.getProductId()).thenReturn(listingProductId);
        when(earlierSku.getSkuId()).thenReturn("SKU-A");
        when(earlierSku.getPriceWon()).thenReturn(1200L);
        when(sellerSkus.findAllBySellerId(listingSellerId)).thenReturn(List.of(laterSku, earlierSku));
        when(sellerSkus.findAllByProductId(listingProductId)).thenReturn(List.of(laterSku, earlierSku));
        when(products.findAll()).thenReturn(List.of(product));
        when(products.findById(listingProductId)).thenReturn(Optional.of(product));
        SellerSkuService listingSkuService = mock(SellerSkuService.class);
        when(listingSkuService.findAll(listingSellerId, List.of("SKU-Z", "SKU-A"))).thenReturn(List.of(
                new SellerSkuResponse(listingSellerId, listingProductId, "SKU-Z", "Tea", 1500),
                new SellerSkuResponse(listingSellerId, listingProductId, "SKU-A", "Tea", 1200)));
        ProductServiceImpl listingService = new ProductServiceImpl(sellers, products, sellerSkus,
                listingSkuService, inventory, Clock.fixed(now, ZoneOffset.UTC));

        List<String> allProductSkus = listingService.listProducts().getFirst().variants().stream()
                .map(SellerSkuResponse::skuId).toList();
        List<String> productSkus = listingService.findProduct(listingProductId).variants().stream()
                .map(SellerSkuResponse::skuId).toList();

        assertEquals(List.of("SKU-A", "SKU-Z"), allProductSkus);
        assertEquals(List.of("SKU-A", "SKU-Z"), productSkus);
    }

    private static Product product(UUID sellerId, UUID productId, String name) {
        Product product = mock(Product.class);
        when(product.getSellerId()).thenReturn(sellerId);
        when(product.getProductId()).thenReturn(productId);
        when(product.getName()).thenReturn(name);
        return product;
    }
}
