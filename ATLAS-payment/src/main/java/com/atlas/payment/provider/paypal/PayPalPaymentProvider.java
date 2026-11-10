package com.atlas.payment.provider.paypal;

import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentOperation;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.provider.PaymentProviderResult;
import com.atlas.payment.provider.PaymentRequest;
import com.atlas.payment.provider.PaymentRefundProviderResult;
import com.atlas.payment.provider.PaymentRefundRequest;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "atlas.payment.provider", havingValue = "paypal")
public class PayPalPaymentProvider implements PaymentProvider {
    private final PayPalRestClient client;

    public PayPalPaymentProvider(PayPalRestClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public PaymentProviderResult perform(PaymentRequest request) {
        try {
            return switch (request.operation()) {
                case AUTHORIZATION -> createOrAuthorize(request);
                case CAPTURE -> capture(request);
                case VOID -> voidAuthorization(request);
            };
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable();
        }
    }

    @Override
    public PaymentProviderResult completeApproval(PaymentRequest request, String returnedOrderId) {
        try {
            if (returnedOrderId == null || returnedOrderId.isBlank()
                    || !returnedOrderId.equals(request.providerOrderId())) {
                throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                        "PayPal order does not belong to this payment attempt");
            }
            PayPalRestClient.OrderStatus order = client.getOrder(returnedOrderId);
        if (order.amount() == null
                || order.amount().compareTo(BigDecimal.valueOf(request.amountMinor())) != 0
                || !"KRW".equals(order.currency()) || !"KRW".equals(request.currency())) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                    "PayPal approval amount or currency does not match the payment attempt");
        }
            return "APPROVED".equals(order.status()) ? authorizeApproved(request) : pending(request);
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable();
        }
    }


    @Override
    public PaymentRefundProviderResult refund(PaymentRefundRequest request) {
        try {
            PayPalRestClient.RefundResult refund = client.refundCapture(request.captureId(),
                    BigDecimal.valueOf(request.amountMinor()), request.currency(), request.requestId());
            return refundResult(refund.id(), refund.status());
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable();
        }
    }

    @Override
    public PaymentRefundProviderResult getRefund(String refundId) {
        try {
            PayPalRestClient.RefundResult refund = client.getRefund(refundId);
            return refundResult(refund.id(), refund.status());
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable();
        }
    }

    @Override
    public PaymentWebhookEvent verifyWebhook(PaymentWebhookSignature signature, String rawBody) {
        try {
            if (!client.verifyWebhook(signature, rawBody).verified()) {
                throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                        "PayPal webhook signature is invalid");
            }
            return client.parseWebhook(rawBody);
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable();
        }
    }

    private static PaymentRefundProviderResult refundResult(String refundId, String status) {
        PaymentRefundProviderResult.Outcome outcome = switch (status == null ? "" : status) {
            case "COMPLETED" -> PaymentRefundProviderResult.Outcome.COMPLETED;
            case "PENDING", "CREATED" -> PaymentRefundProviderResult.Outcome.PENDING;
            case "FAILED", "DENIED", "CANCELLED" -> PaymentRefundProviderResult.Outcome.FAILED;
            default -> PaymentRefundProviderResult.Outcome.UNKNOWN;
        };
        if (refundId == null && outcome != PaymentRefundProviderResult.Outcome.FAILED) {
            outcome = PaymentRefundProviderResult.Outcome.UNKNOWN;
        }
        return new PaymentRefundProviderResult(outcome, refundId, status);
    }
    private PaymentProviderResult createOrAuthorize(PaymentRequest request) {
        if (request.providerOrderId() == null) {
            PayPalRestClient.OrderResult order = client.createOrder(request.ownerId().toString(),
                    BigDecimal.valueOf(request.amountMinor()), request.currency(), request.returnUrl(),
                    request.cancelUrl(), request.createOrderRequestId());
            if (order.id() == null || order.approvalUrl() == null) return unknown(request);
            PaymentRequest withOrder = withOrder(request, order.id());
            if ("APPROVED".equals(order.status())) {
                PaymentProviderResult authorized = authorizeApproved(withOrder);
                return result(authorized.outcome(), order.id(), authorized.authorizationId(),
                        authorized.captureId(), order.approvalUrl());
            }
            return result(PaymentProviderResult.Outcome.PENDING, order.id(), null, null, order.approvalUrl());
        }
        PayPalRestClient.OrderStatus order = client.getOrder(request.providerOrderId());
        return "APPROVED".equals(order.status()) ? authorizeApproved(request) : pending(request);
    }

    private PaymentProviderResult authorizeApproved(PaymentRequest request) {
        PayPalRestClient.AuthorizationResult authorization = client.authorizeOrder(
                request.providerOrderId(), request.authorizationRequestId());
        if (authorization.id() == null) return unknown(request);
        PaymentProviderResult.Outcome outcome = switch (authorization.status()) {
            case "CREATED" -> PaymentProviderResult.Outcome.AUTHORIZED;
            case "PENDING" -> PaymentProviderResult.Outcome.PENDING;
            case "DENIED" -> PaymentProviderResult.Outcome.DECLINED;
            case "VOIDED" -> PaymentProviderResult.Outcome.VOIDED;
            case "CAPTURED", "PARTIALLY_CAPTURED" -> PaymentProviderResult.Outcome.CAPTURED;
            default -> PaymentProviderResult.Outcome.UNKNOWN;
        };
        return result(outcome, request.providerOrderId(), authorization.id(), null, null);
    }

    private PaymentProviderResult capture(PaymentRequest request) {
        if (request.providerAuthorizationId() == null) return unknown(request);
        PayPalRestClient.CaptureResult capture = client.captureAuthorization(request.providerAuthorizationId(),
                BigDecimal.valueOf(request.amountMinor()), request.currency(), request.captureRequestId());
        PaymentProviderResult.Outcome outcome = "COMPLETED".equals(capture.status())
                ? PaymentProviderResult.Outcome.CAPTURED
                : "PENDING".equals(capture.status()) ? PaymentProviderResult.Outcome.PENDING
                : PaymentProviderResult.Outcome.UNKNOWN;
        return result(outcome, request.providerOrderId(), request.providerAuthorizationId(), capture.id(), null);
    }

    private PaymentProviderResult voidAuthorization(PaymentRequest request) {
        if (request.providerAuthorizationId() == null) {
            return result(PaymentProviderResult.Outcome.VOIDED, request.providerOrderId(), null, null, null);
        }
        client.voidAuthorization(request.providerAuthorizationId(), request.voidRequestId());
        return result(PaymentProviderResult.Outcome.VOIDED, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    private static PaymentRequest withOrder(PaymentRequest request, String orderId) {
        return new PaymentRequest(request.operation(), request.ownerId(), request.attemptId(), request.amountMinor(),
                request.currency(), orderId, request.providerAuthorizationId(), request.createOrderRequestId(),
                request.authorizationRequestId(), request.captureRequestId(), request.voidRequestId(),
                request.returnUrl(), request.cancelUrl());
    }

    private static PaymentProviderResult pending(PaymentRequest request) {
        return result(PaymentProviderResult.Outcome.PENDING, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    private static PaymentProviderResult unknown(PaymentRequest request) {
        return result(PaymentProviderResult.Outcome.UNKNOWN, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    private static PaymentProviderResult result(PaymentProviderResult.Outcome outcome, String orderId,
            String authorizationId, String captureId, String approvalUrl) {
        return new PaymentProviderResult(outcome, orderId, authorizationId, captureId, approvalUrl);
    }

    private static PaymentException unavailable() {
        return new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                "PayPal payment provider is unavailable");
    }
}
