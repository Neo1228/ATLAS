package com.atlas.order.internal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CheckoutExpiryScheduler {

    private final OrderService orders;

    CheckoutExpiryScheduler(OrderService orders) {
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public int expireDueCheckouts() {
        return orders.expireDueCheckouts();
    }
}
