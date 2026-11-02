package com.atlas.order;

import java.util.UUID;

public interface OrderApi {

    OrderView startCheckout(UUID campaignId, UUID ticketId);

    OrderView submitPayment(UUID orderId);

    OrderView reconcilePayment(UUID orderId);

    OrderView cancel(UUID orderId);

    OrderView find(UUID orderId);

    int expireCheckoutWindows();
}
