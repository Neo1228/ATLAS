package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.RegisterSellerSkuHttpRequest;
import com.atlas.campaign.dto.request.RegisterSellerSkuRequest;
import com.atlas.campaign.dto.response.SellerSkuResponse;
import com.atlas.campaign.service.SellerSkuService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}/skus")
public class SellerSkuController {

    private final SellerSkuService sellerSkus;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SellerSkuResponse register(@PathVariable UUID sellerId,
            @Valid @RequestBody RegisterSellerSkuHttpRequest request) {
        return sellerSkus.register(sellerId, new RegisterSellerSkuRequest(request.skuId(),
                request.initialStock(), request.maxActiveCheckouts()));
    }
}
