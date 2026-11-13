package com.atlas.payment.persistence.entity;

import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    @Column(name = "provider_amount_minor")
    @Getter private Long providerAmountMinor;
    @Column(name = "provider_currency", length = 3)
    @Getter private String providerCurrency;
    @Column(name = "fx_rate_won_per_usd", precision = 20, scale = 10)
    @Getter private BigDecimal fxRateWonPerUsd;
    @Column(name = "fx_rate_source", length = 64)
    @Getter private String fxRateSource;
    @Column(name = "fx_rate_as_of")
    @Getter private LocalDate fxRateAsOf;
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
    public boolean hasFxSnapshot() {
        return providerAmountMinor != null && providerAmountMinor > 0
                && "USD".equals(providerCurrency) && fxRateWonPerUsd != null
                && fxRateWonPerUsd.signum() > 0 && fxRateSource != null && fxRateAsOf != null;
    }

    public void setFxSnapshot(BigDecimal rate, String source, LocalDate asOfDate,
            long providerAmountMinor, String providerCurrency) {
        boolean partialSnapshot = this.providerAmountMinor != null || this.providerCurrency != null
                || this.fxRateWonPerUsd != null || this.fxRateSource != null || this.fxRateAsOf != null;
        if (providerAmountMinor <= 0 || rate == null || rate.signum() <= 0
                || source == null || source.isBlank() || asOfDate == null || !"USD".equals(providerCurrency)) {
            throw new IllegalArgumentException("A valid USD exchange-rate snapshot is required");
        }
        if (partialSnapshot) {
            if (!hasFxSnapshot() || this.providerAmountMinor != providerAmountMinor
                    || !this.providerCurrency.equals(providerCurrency)
                    || this.fxRateWonPerUsd.compareTo(rate) != 0
                    || !this.fxRateSource.equals(source) || !this.fxRateAsOf.equals(asOfDate)) {
                throw new IllegalStateException("Payment FX snapshot is immutable");
            }
            return;
        }
        this.providerAmountMinor = providerAmountMinor;
        this.providerCurrency = providerCurrency;
        this.fxRateWonPerUsd = rate;
        this.fxRateSource = source;
        this.fxRateAsOf = asOfDate;
    }
}
