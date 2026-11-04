package com.atlas.cart.service.impl;

import com.atlas.cart.dto.response.CartItemResponse;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.exception.CartException;
import com.atlas.cart.persistence.entity.Cart;
import com.atlas.cart.persistence.entity.CartItem;
import com.atlas.cart.repository.CartRepository;
import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
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
