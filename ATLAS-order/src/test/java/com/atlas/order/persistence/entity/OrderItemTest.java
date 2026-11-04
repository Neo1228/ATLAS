package com.atlas.order.persistence.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atlas.order.persistence.state.OrderState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderItemTest {
    private static PurchaseOrder cartOrder() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PurchaseOrder(UUID.randomUUID(), "buyer", "checkout-key", OrderState.RESERVED,
                now.plusSeconds(600), now, now);
    }

    @Test
    void lineTotalUsesWonSnapshotAndQuantity() {
        OrderItem item = new OrderItem(cartOrder(), UUID.randomUUID(), UUID.randomUUID(), "SKU-1",
                "Product", 3, 1_250);

        assertEquals(3_750, item.lineTotalWon());
    }

    @Test
    void rejectsLineTotalOverflowAtSnapshotCreation() {
        assertThrows(ArithmeticException.class, () -> new OrderItem(cartOrder(), UUID.randomUUID(),
                UUID.randomUUID(), "SKU-1", "Product", 2, Long.MAX_VALUE));
    }

    @Test
    void rejectsZeroWonPrice() {
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(cartOrder(), UUID.randomUUID(),
                UUID.randomUUID(), "SKU-1", "Product", 1, 0));
    }

    @Test
    void cartOrdersHaveNoCampaignOrTicketMetadata() {
        PurchaseOrder order = cartOrder();

        assertEquals(null, order.campaignId());
        assertEquals(null, order.ticketId());
        assertEquals("checkout-key", order.checkoutKey());
    }
}
