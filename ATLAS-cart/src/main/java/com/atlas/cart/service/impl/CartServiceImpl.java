package com.atlas.cart.service.impl;

import com.atlas.cart.dto.request.ReorderCartRequest;
import com.atlas.cart.dto.response.CartItemResponse;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.exception.CartException;
import com.atlas.cart.persistence.entity.Cart;
import com.atlas.cart.persistence.entity.CartItem;
import com.atlas.cart.repository.CartRepository;
import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartServiceImpl implements CartService {
    private final CartRepository carts;
    private final SellerSkuService sellerSkus;

    public CartServiceImpl(CartRepository carts, SellerSkuService sellerSkus) {
        this.carts = Objects.requireNonNull(carts, "carts");
        this.sellerSkus = Objects.requireNonNull(sellerSkus, "sellerSkus");
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse find(UUID sellerId, String buyerRef) {
        validateIdentity(sellerId, buyerRef);
        return carts.findBySellerIdAndBuyerRef(sellerId, buyerRef)
                .map(this::toResponse)
                .orElseThrow(() -> new CartException(CartException.Code.NOT_FOUND, "Cart not found"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CartResponse mergeItems(ReorderCartRequest request) {
        if (request == null) {
            throw new CartException(CartException.Code.INVALID_REQUEST, "request is required");
        }
        validateIdentity(request.sellerId(), request.buyerRef());
        Map<String, Integer> quantities = validatedReorderQuantities(request);

        Cart cart = carts.findWithLockBySellerIdAndBuyerRef(request.sellerId(), request.buyerRef())
                .orElseGet(() -> carts.save(new Cart(request.sellerId(), request.buyerRef())));
        if (!cart.items().isEmpty() && !request.confirmExistingCartMerge()) {
            throw new CartException(CartException.Code.CONFLICT, "cart merge confirmation is required");
        }

        Map<String, Integer> mergedQuantities = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> requested : quantities.entrySet()) {
            CartItem existing = cart.items().stream()
                    .filter(item -> item.skuId().equals(requested.getKey()))
                    .findFirst()
                    .orElse(null);
            try {
                mergedQuantities.put(requested.getKey(),
                        Math.addExact(existing == null ? 0 : existing.quantity(), requested.getValue()));
            } catch (ArithmeticException overflow) {
                throw new CartException(CartException.Code.INVALID_REQUEST, "merged quantity is too large");
            }
        }
        mergedQuantities.forEach(cart::setItemQuantity);
        return toResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse setItemQuantity(UUID sellerId, String buyerRef, String skuId, int quantity) {
        validateIdentity(sellerId, buyerRef);
        validateSkuId(skuId);
        if (quantity <= 0) {
            throw new CartException(CartException.Code.INVALID_REQUEST, "quantity must be positive");
        }
        requireSellerSku(sellerId, skuId);
        Cart cart = carts.findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef)
                .orElseGet(() -> carts.save(new Cart(sellerId, buyerRef)));
        cart.setItemQuantity(skuId, quantity);
        return toResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(UUID sellerId, String buyerRef, String skuId) {
        validateIdentity(sellerId, buyerRef);
        validateSkuId(skuId);
        Cart cart = requireLockedCart(sellerId, buyerRef);
        cart.removeItem(skuId);
        return toResponse(cart);
    }

    @Override
    @Transactional
    public void clearItems(UUID sellerId, String buyerRef) {
        validateIdentity(sellerId, buyerRef);
        requireLockedCart(sellerId, buyerRef).clearItems();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CartResponse lockForCheckout(UUID sellerId, String buyerRef) {
        validateIdentity(sellerId, buyerRef);
        return toResponse(requireLockedCart(sellerId, buyerRef));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearForCheckout(UUID sellerId, String buyerRef) {
        validateIdentity(sellerId, buyerRef);
        requireLockedCart(sellerId, buyerRef).clearItems();
    }

    private Map<String, Integer> validatedReorderQuantities(ReorderCartRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new CartException(CartException.Code.INVALID_REQUEST, "at least one item is required");
        }
        Map<String, Integer> quantities = new LinkedHashMap<>();
        Map<String, UUID> productIds = new LinkedHashMap<>();
        for (ReorderCartRequest.Item item : request.items()) {
            if (item == null) {
                throw new CartException(CartException.Code.INVALID_REQUEST, "item is required");
            }
            validateSkuId(item.skuId());
            if (item.productId() == null) {
                throw new CartException(CartException.Code.INVALID_REQUEST, "productId is required");
            }
            if (item.quantity() <= 0) {
                throw new CartException(CartException.Code.INVALID_REQUEST, "quantity must be positive");
            }
            try {
                UUID previousProductId = productIds.putIfAbsent(item.skuId(), item.productId());
                if (previousProductId != null && !previousProductId.equals(item.productId())) {
                    throw new CartException(CartException.Code.INVALID_REQUEST,
                            "skuId cannot refer to multiple products");
                }
                quantities.merge(item.skuId(), item.quantity(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new CartException(CartException.Code.INVALID_REQUEST, "quantity is too large");
            }
        }

        List<SellerSkuResponse> found = sellerSkus.findAll(request.sellerId(), List.copyOf(quantities.keySet()));
        Map<String, SellerSkuResponse> foundById = found.stream()
                .filter(sku -> request.sellerId().equals(sku.sellerId()))
                .collect(Collectors.toMap(SellerSkuResponse::skuId, sku -> sku));
        if (foundById.size() != quantities.size() || !foundById.keySet().containsAll(quantities.keySet())) {
            throw new CartException(CartException.Code.SKU_NOT_FOUND, "seller SKU not found");
        }
        for (Map.Entry<String, UUID> requested : productIds.entrySet()) {
            if (!requested.getValue().equals(foundById.get(requested.getKey()).productId())) {
                throw new CartException(CartException.Code.SKU_NOT_FOUND,
                        "seller SKU no longer belongs to the source product");
            }
        }
        return quantities;
    }

    private Cart requireLockedCart(UUID sellerId, String buyerRef) {
        return carts.findWithLockBySellerIdAndBuyerRef(sellerId, buyerRef)
                .orElseThrow(() -> new CartException(CartException.Code.NOT_FOUND, "cart not found"));
    }

    private SellerSkuResponse requireSellerSku(UUID sellerId, String skuId) {
        SellerSkuResponse sku = sellerSkus.find(sellerId, skuId);
        if (sku == null || !sellerId.equals(sku.sellerId())) {
            throw new CartException(CartException.Code.SKU_NOT_FOUND, "seller SKU not found");
        }
        return sku;
    }

    private CartResponse toResponse(Cart cart) {
        List<String> skuIds = cart.items().stream()
                .map(CartItem::skuId)
                .toList();
        Map<String, SellerSkuResponse> skuById = sellerSkus.findAll(cart.sellerId(), skuIds).stream()
                .filter(sku -> cart.sellerId().equals(sku.sellerId()))
                .collect(Collectors.toMap(SellerSkuResponse::skuId, sku -> sku));
        List<CartItemResponse> items = cart.items().stream()
                .map(item -> toItemResponse(item, skuById))
                .toList();
        return new CartResponse(cart.cartId(), cart.sellerId(), cart.buyerRef(), items);
    }

    private CartItemResponse toItemResponse(CartItem item, Map<String, SellerSkuResponse> skuById) {
        SellerSkuResponse sku = skuById.get(item.skuId());
        if (sku == null) {
            throw new CartException(CartException.Code.SKU_NOT_FOUND, "seller SKU not found");
        }
        return new CartItemResponse(sku.productId(), sku.skuId(), item.quantity(),
                sku.productName(), sku.priceWon());
    }

    private static void validateIdentity(UUID sellerId, String buyerRef) {
        if (sellerId == null || buyerRef == null || buyerRef.isBlank() || buyerRef.length() > 256) {
            throw new CartException(CartException.Code.INVALID_REQUEST, "sellerId and buyerRef are required");
        }
    }

    private static void validateSkuId(String skuId) {
        if (skuId == null || skuId.isBlank() || skuId.length() > 128) {
            throw new CartException(CartException.Code.INVALID_REQUEST, "skuId is required");
        }
    }
}
