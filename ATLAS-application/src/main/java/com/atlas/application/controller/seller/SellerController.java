package com.atlas.application.controller.seller;

import com.atlas.application.service.SellerOnboardingService;
import com.atlas.catalog.dto.response.SellerResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sellers")
public class SellerController {
    private final SellerOnboardingService onboarding;

    public SellerController(SellerOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SellerResponse create(@AuthenticationPrincipal Jwt jwt) {
        return onboarding.createSeller(UUID.fromString(jwt.getSubject()));
    }
}
