package com.atlas.order.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.persistence.state.BuyerCheckoutState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BuyerCheckoutTest {
    @Test
    void repeatedCancelPendingUpdatesDoNotResetCancellationRequestedAt() {
        Instant createdAt = Instant.parse("2026-11-22T12:00:00Z");
        Instant cancelRequestedAt = createdAt.plusSeconds(5);
        Instant retryUpdatedAt = cancelRequestedAt.plusSeconds(30);
        BuyerCheckout checkout = new BuyerCheckout("buyer", "checkout-key", BuyerCheckoutState.RESERVED,
                100, createdAt, createdAt);

        checkout.update(BuyerCheckoutState.CANCEL_PENDING, 100, cancelRequestedAt);
        checkout.update(BuyerCheckoutState.CANCEL_PENDING, 100, retryUpdatedAt);

        assertThat(checkout.cancellationRequestedAt()).isEqualTo(cancelRequestedAt);
        assertThat(checkout.updatedAt()).isEqualTo(retryUpdatedAt);
    }
}
