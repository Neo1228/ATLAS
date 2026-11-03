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

@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "attempt_id", nullable = false, unique = true)
    private UUID attemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_phase", nullable = false, length = 20)
    private PaymentPhase paymentPhase;

    @Column(name = "authorization_active", nullable = false)
    private boolean authorizationActive;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(UUID orderId, UUID attemptId, PaymentState state, PaymentPhase paymentPhase,
                                boolean authorizationActive) {
        this.orderId = orderId;
        this.attemptId = attemptId;
        this.state = state;
        this.paymentPhase = paymentPhase;
        this.authorizationActive = authorizationActive;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getAttemptId() { return attemptId; }
    public PaymentState getState() { return state; }
    public PaymentPhase getPaymentPhase() { return paymentPhase; }
    public boolean isAuthorizationActive() { return authorizationActive; }

    public void setState(PaymentState state) { this.state = state; }
    public void setPaymentPhase(PaymentPhase paymentPhase) { this.paymentPhase = paymentPhase; }
    public void setAuthorizationActive(boolean authorizationActive) { this.authorizationActive = authorizationActive; }
}
