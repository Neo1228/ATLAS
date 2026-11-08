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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartServiceImpl implements CartService {
    private final CartRepository carts;
    private final SellerSkuService sellerSkus;

    public CartServiceImpl(CartRepository carts, SellerSkuService sellerSkus) {
        this.carts = Objects.requireNonNull(carts);
        this.sellerSkus = Objects.requireNonNull(sellerSkus);
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse find(String buyerRef) {
        validateBuyer(buyerRef);
        return carts.findByBuyerRef(buyerRef).map(this::toResponse)
                .orElseThrow(() -> new CartException(CartException.Code.NOT_FOUND, "Cart not found"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CartResponse mergeItems(ReorderCartRequest request) {
        if (request == null) {
            throw invalid("request required");
        }
        validateBuyer(request.buyerRef());
        if (request.items() == null || request.items().isEmpty()) {
            throw invalid("at least one item required");
        }

        Map<Key, Integer> quantities = new LinkedHashMap<>();
        Map<Key, UUID> products = new LinkedHashMap<>();
        for (ReorderCartRequest.Item item : request.items()) {
            if (item == null || item.sellerId() == null || item.productId() == null || item.quantity() <= 0) {
                throw invalid("valid sellerId, productId and positive quantity required");
            }
            validateSku(item.skuId());
            Key key = new Key(item.sellerId(), item.skuId());
            UUID previousProduct = products.putIfAbsent(key, item.productId());
            if (previousProduct != null && !previousProduct.equals(item.productId())) {
                throw invalid("skuId cannot refer to multiple products");
            }
            try {
                quantities.merge(key, item.quantity(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw invalid("quantity too large");
            }
        }

        for (Key key : quantities.keySet()) {
            SellerSkuResponse sku = requireSellerSku(key.sellerId(), key.skuId());
            if (!products.get(key).equals(sku.productId())) {
                throw new CartException(CartException.Code.SKU_NOT_FOUND,
                        "seller SKU no longer belongs to source product");
            }
        }

        Cart cart = lockOrCreateCart(request.buyerRef());
        if (!cart.items().isEmpty() && !request.confirmExistingCartMerge()) {
            throw new CartException(CartException.Code.CONFLICT, "cart merge confirmation required");
        }
        for (Map.Entry<Key, Integer> entry : quantities.entrySet()) {
            Key key = entry.getKey();
            CartItem existing = cart.items().stream()
                    .filter(item -> item.sellerId().equals(key.sellerId()) && item.skuId().equals(key.skuId()))
                    .findFirst().orElse(null);
            int quantity;
            try {
                quantity = Math.addExact(existing == null ? 0 : existing.quantity(), entry.getValue());
            } catch (ArithmeticException overflow) {
                throw invalid("merged quantity is too large");
            }
            cart.setItemQuantity(key.sellerId(), key.skuId(), quantity);
        }
        return toResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse setItemQuantity(UUID sellerId, String buyerRef, String skuId, int quantity) {
        validateLine(sellerId, buyerRef, skuId);
        if (quantity <= 0) {
            throw invalid("quantity must be positive");
        }
        requireSellerSku(sellerId, skuId);
        Cart cart = lockOrCreateCart(buyerRef);
        cart.setItemQuantity(sellerId, skuId, quantity);
        return toResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(UUID sellerId, String buyerRef, String skuId) {
        validateLine(sellerId, buyerRef, skuId);
        Cart cart = requireLockedCart(buyerRef);
        cart.removeItem(sellerId, skuId);
        return toResponse(cart);
    }

    @Override
    @Transactional
    public void clearItems(String buyerRef) {
        validateBuyer(buyerRef);
        requireLockedCart(buyerRef).clearItems();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CartResponse lockForCheckout(String buyerRef) {
        validateBuyer(buyerRef);
        return toResponse(requireLockedCart(buyerRef));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearForCheckout(String buyerRef) {
        validateBuyer(buyerRef);
        requireLockedCart(buyerRef).clearItems();
    }

    private Cart lockOrCreateCart(String buyerRef) {
        carts.insertIfAbsentByBuyerRef(buyerRef);
        return carts.findWithLockByBuyerRef(buyerRef)
                .orElseThrow(() -> new CartException(CartException.Code.NOT_FOUND, "cart not found"));
    }

    private Cart requireLockedCart(String buyerRef) {
        return carts.findWithLockByBuyerRef(buyerRef)
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
        List<CartItemResponse> items = cart.items().stream().map(item -> {
            SellerSkuResponse sku = requireSellerSku(item.sellerId(), item.skuId());
            return new CartItemResponse(item.sellerId(), sku.productId(), sku.skuId(), item.quantity(),
                    sku.productName(), sku.effectivePriceWon());
        }).toList();
        return new CartResponse(cart.cartId(), cart.buyerRef(), items);
    }

    private static void validateLine(UUID sellerId, String buyerRef, String skuId) {
        if (sellerId == null) {
            throw invalid("sellerId is required");
        }
        validateBuyer(buyerRef);
        validateSku(skuId);
    }

    private static void validateBuyer(String buyerRef) {
        if (buyerRef == null || buyerRef.isBlank() || buyerRef.length() > 256) {
            throw invalid("buyerRef is required");
        }
    }

    private static void validateSku(String skuId) {
        if (skuId == null || skuId.isBlank() || skuId.length() > 128) {
            throw invalid("skuId is required");
        }
    }

    private static CartException invalid(String message) {
        return new CartException(CartException.Code.INVALID_REQUEST, message);
    }

    private record Key(UUID sellerId, String skuId) {
    }
}
