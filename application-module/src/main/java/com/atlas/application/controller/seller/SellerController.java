package com.atlas.application.controller.seller;

import com.atlas.campaign.dto.response.SellerResponse;
import com.atlas.campaign.service.SellerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sellers")
public class SellerController {

    private final SellerService sellers;

    public SellerController(SellerService sellers) {
        this.sellers = sellers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SellerResponse create() {
        return sellers.create();
    }
}
