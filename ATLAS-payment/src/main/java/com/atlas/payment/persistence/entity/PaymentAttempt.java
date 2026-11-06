package com.atlas.payment.persistence.entity;

import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    @Column(name = "order_id", unique = true)
    @Getter
    private UUID orderId;

    @Column(name = "checkout_id", unique = true)
    @Getter
    private UUID checkoutId;

    @Id
    @Column(name = "attempt_id", nullable = false)
    @Getter
    private UUID attemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Getter
    private PaymentState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_phase", nullable = false, length = 20)
    @Getter
    private PaymentPhase paymentPhase;

    @Column(name = "authorization_active", nullable = false)
    @Getter
    private boolean authorizationActive;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(UUID orderId, UUID checkoutId, UUID attemptId, PaymentState state,
                          PaymentPhase paymentPhase, boolean authorizationActive) {
        if ((orderId == null) == (checkoutId == null)) {
            throw new IllegalArgumentException("Exactly one payment owner is required");
        }
        this.orderId = orderId;
        this.checkoutId = checkoutId;
        this.attemptId = attemptId;
        this.state = state;
        this.paymentPhase = paymentPhase;
        this.authorizationActive = authorizationActive;
    }

    public PaymentAttempt(UUID orderId, UUID attemptId, PaymentState state, PaymentPhase paymentPhase,
                          boolean authorizationActive) {
        this(orderId, null, attemptId, state, paymentPhase, authorizationActive);
    }

    public void setState(PaymentState state) {
        this.state = state;
    }

    public void setPaymentPhase(PaymentPhase paymentPhase) {
        this.paymentPhase = paymentPhase;
    }

    public void setAuthorizationActive(boolean authorizationActive) {
        this.authorizationActive = authorizationActive;
    }
}
