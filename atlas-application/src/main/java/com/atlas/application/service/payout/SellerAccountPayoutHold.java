package com.atlas.application.service.payout;

import com.atlas.account.service.AccountService;
import com.atlas.settlement.service.PayoutHoldCheck;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Seller 계정이 정지·폐쇄돼 있으면 송금을 보류한다(ADR-0112). 판정은 판매 차단과 같은 {@code isSellerAccountDisabled}다. */
@Component
public class SellerAccountPayoutHold implements PayoutHoldCheck {
    static final String SELLER_ACCOUNT_DISABLED = "SELLER_ACCOUNT_DISABLED";

    private final AccountService accounts;

    public SellerAccountPayoutHold(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<String> holdReason(UUID sellerId) {
        return accounts.isSellerAccountDisabled(sellerId) ? Optional.of(SELLER_ACCOUNT_DISABLED) : Optional.empty();
    }
}
