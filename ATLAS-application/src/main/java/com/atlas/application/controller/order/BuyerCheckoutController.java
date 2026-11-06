package com.atlas.application.controller.order;

import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.service.CheckoutPaymentService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/checkouts/{checkoutId}")
public class BuyerCheckoutController {
    private final CheckoutPaymentService checkoutPayments;

    @PostMapping("/payment")
    public CheckoutResponse submit(@PathVariable UUID checkoutId) {
        return checkoutPayments.submit(checkoutId);
    }

    @PostMapping("/reconcile")
    public CheckoutResponse reconcile(@PathVariable UUID checkoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return checkoutPayments.reconcile(checkoutId, idempotencyKey);
    }

    @PostMapping("/cancel")
    public CheckoutResponse cancel(@PathVariable UUID checkoutId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return checkoutPayments.cancel(checkoutId, idempotencyKey);
    }

    @GetMapping
    public CheckoutResponse find(@PathVariable UUID checkoutId) {
        return checkoutPayments.find(checkoutId);
    }
}
