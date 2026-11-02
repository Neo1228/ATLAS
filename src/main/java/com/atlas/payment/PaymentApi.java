package com.atlas.payment;

import java.util.UUID;

public interface PaymentApi {

    PaymentView submitInitial(UUID orderId);

    PaymentView reconcileForCancellation(UUID orderId);
    PaymentView reconcile(UUID orderId);

    PaymentView voidAuthorization(UUID orderId);

    PaymentView find(UUID orderId);
}
