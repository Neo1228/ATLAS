package com.atlas.application.controller.catalog;

import com.atlas.catalog.dto.response.BuyerWishlistResponse;
import com.atlas.catalog.service.BuyerWishlistService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/buyers/{buyerRef}/wishlist")
public class BuyerWishlistController {
    private final BuyerWishlistService wishlist;

    public BuyerWishlistController(BuyerWishlistService wishlist) {
        this.wishlist = wishlist;
    }

    @GetMapping
    public BuyerWishlistResponse.Page list(@PathVariable String buyerRef,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return wishlist.list(buyerRef, limit, offset);
    }

    @PostMapping("/products/{productId}")
    public BuyerWishlistResponse.Item save(@PathVariable String buyerRef, @PathVariable UUID productId) {
        return wishlist.save(buyerRef, productId);
    }

    @DeleteMapping("/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String buyerRef, @PathVariable UUID productId) {
        wishlist.remove(buyerRef, productId);
    }
}
