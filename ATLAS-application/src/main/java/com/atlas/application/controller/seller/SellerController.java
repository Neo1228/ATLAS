package com.atlas.application.controller.seller;

import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.service.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers")
public class SellerController {

    private final SellerService sellers;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SellerResponse create() {
        return sellers.createSeller();
    }
}
