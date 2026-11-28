package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.account.service.AccountService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerAccountPayoutHoldTest {
    @Test
    void holdsOnlyWhileSellerAccountIsDisabled() {
        AccountService accounts = mock(AccountService.class);
        UUID disabled = UUID.randomUUID();
        when(accounts.isSellerAccountDisabled(disabled)).thenReturn(true);
        SellerAccountPayoutHold hold = new SellerAccountPayoutHold(accounts);

        assertThat(hold.holdReason(disabled)).contains("SELLER_ACCOUNT_DISABLED");
        assertThat(hold.holdReason(UUID.randomUUID())).isEmpty();
    }
}
