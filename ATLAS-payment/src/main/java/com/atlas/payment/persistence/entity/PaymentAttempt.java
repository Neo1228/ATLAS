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
    @Getter private UUID orderId;
    @Column(name = "checkout_id", unique = true)
    @Getter private UUID checkoutId;
    @Id
    @Column(name = "attempt_id", nullable = false)
    @Getter private UUID attemptId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Getter private PaymentState state;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_phase", nullable = false, length = 20)
    @Getter private PaymentPhase paymentPhase;
    @Column(name = "authorization_active", nullable = false)
    @Getter private boolean authorizationActive;
    @Column(name = "total_won")
    @Getter private Long totalWon;
    @Column(name = "provider_order_id", unique = true, length = 64)
    @Getter private String providerOrderId;
    @Column(name = "provider_authorization_id", unique = true, length = 64)
    @Getter private String providerAuthorizationId;
    @Column(name = "provider_capture_id", unique = true, length = 64)
    @Getter private String providerCaptureId;
    @Column(name = "approval_url", columnDefinition = "text")
    @Getter private String approvalUrl;
    @Column(name = "create_order_request_id", length = 38)
    @Getter private String createOrderRequestId;
    @Column(name = "authorization_request_id", length = 38)
    @Getter private String authorizationRequestId;
    @Column(name = "capture_request_id", length = 38)
    @Getter private String captureRequestId;
    @Column(name = "void_request_id", length = 38)
    @Getter private String voidRequestId;

    protected PaymentAttempt() { }

    public PaymentAttempt(UUID orderId, UUID checkoutId, UUID attemptId, PaymentState state,
            PaymentPhase paymentPhase, boolean authorizationActive) {
        if ((orderId == null) == (checkoutId == null)) {
            throw new IllegalArgumentException("Exactly one payment owner required");
        }
        this.orderId = orderId;
        this.checkoutId = checkoutId;
        this.attemptId = attemptId;
        this.state = state;
        this.paymentPhase = paymentPhase;
        this.authorizationActive = authorizationActive;
    }

    public PaymentAttempt(UUID orderId, UUID attemptId, PaymentState state,
            PaymentPhase paymentPhase, boolean authorizationActive) {
        this(orderId, null, attemptId, state, paymentPhase, authorizationActive);
    }

    public void setState(PaymentState state) { this.state = state; }
    public void setPaymentPhase(PaymentPhase paymentPhase) { this.paymentPhase = paymentPhase; }
    public void setAuthorizationActive(boolean authorizationActive) { this.authorizationActive = authorizationActive; }
    public void setTotalWon(long totalWon) {
        if (totalWon <= 0) throw new IllegalArgumentException("Payment amount must be positive");
        this.totalWon = totalWon;
    }
    public void setProviderOrderId(String value) { this.providerOrderId = value; }
    public void setProviderAuthorizationId(String value) { this.providerAuthorizationId = value; }
    public void setProviderCaptureId(String value) { this.providerCaptureId = value; }
    public void setApprovalUrl(String value) { this.approvalUrl = value; }
    public void setCreateOrderRequestId(String value) { this.createOrderRequestId = value; }
    public void setAuthorizationRequestId(String value) { this.authorizationRequestId = value; }
    public void setCaptureRequestId(String value) { this.captureRequestId = value; }
    public void setVoidRequestId(String value) { this.voidRequestId = value; }
}
