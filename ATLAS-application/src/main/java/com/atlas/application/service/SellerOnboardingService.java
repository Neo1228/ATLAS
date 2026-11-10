package com.atlas.application.service;

import com.atlas.account.service.AccountService;
import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.service.SellerService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerOnboardingService {
    private final SellerService sellers;
    private final AccountService accounts;

    public SellerOnboardingService(SellerService sellers, AccountService accounts) {
        this.sellers = sellers;
        this.accounts = accounts;
    }

    @Transactional
    public SellerResponse createSeller(UUID accountId) {
        SellerResponse seller = sellers.createSeller();
        accounts.linkSeller(accountId, seller.sellerId());
        return seller;
    }
}
