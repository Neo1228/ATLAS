package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.account.service.AccountService;
import com.atlas.payment.provider.paypal.PayPalPayoutsProperties;
import com.atlas.settlement.exception.SettlementException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerPayoutProfileServiceTest {
    private final SellerPayoutProfileRepository repository = mock(SellerPayoutProfileRepository.class);
    private final AccountService accounts = mock(AccountService.class);
    private final SellerPayoutProfileService service = new SellerPayoutProfileService(
            repository, accounts, new PayPalPayoutsProperties(), Clock.systemUTC());

    @Test
    void rejectsProfileReadsFromAnAccountThatDoesNotOwnTheSeller() {
        UUID accountId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        when(accounts.ownsSeller(accountId, sellerId)).thenReturn(false);

        assertThatThrownBy(() -> service.find(accountId, sellerId))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.code()).isEqualTo(SettlementException.Code.NOT_FOUND));
        verify(repository, never()).find(sellerId);
    }
}
