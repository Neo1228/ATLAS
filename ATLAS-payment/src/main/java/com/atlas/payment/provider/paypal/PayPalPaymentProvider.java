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
            validateOrderAmount(request, order);
            return orderStatusResult(request, order);
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
            if (order.id() == null) return unknown(request);
            PaymentRequest withOrder = withOrder(request, order.id());
            String status = order.status() == null ? "" : order.status();
            if ("APPROVED".equals(status) || "COMPLETED".equals(status)) {
                PayPalRestClient.OrderStatus current = client.getOrder(order.id());
                validateOrderAmount(withOrder, current);
                return orderStatusResult(withOrder, current);
            }
            if ("VOIDED".equals(status)) {
                return result(PaymentProviderResult.Outcome.VOIDED, order.id(), null, null, null);
            }
            if (isAwaitingPayer(status)) {
                return order.approvalUrl() == null ? unknown(withOrder)
                        : result(PaymentProviderResult.Outcome.PENDING, order.id(), null, null, order.approvalUrl());
            }
            return unknown(withOrder);
        }

        PayPalRestClient.OrderStatus order = client.getOrder(request.providerOrderId());
        validateOrderAmount(request, order);
        return orderStatusResult(request, order);
    }

    private PaymentProviderResult orderStatusResult(PaymentRequest request, PayPalRestClient.OrderStatus order) {
        if (order.id() == null || !order.id().equals(request.providerOrderId())) return unknown(request);
        String status = order.status() == null ? "" : order.status();
        return switch (status) {
            case "APPROVED" -> authorizeApproved(request);
            case "CREATED", "SAVED", "PAYER_ACTION_REQUIRED" -> pending(request);
            case "VOIDED" -> result(PaymentProviderResult.Outcome.VOIDED, order.id(),
                    order.authorizationId(), order.captureId(), null);
            case "COMPLETED" -> completedOrderResult(request, order);
            default -> unknown(request);
        };
    }

    private static PaymentProviderResult completedOrderResult(
            PaymentRequest request, PayPalRestClient.OrderStatus order) {
        if (order.captureId() != null) {
            return switch (order.captureStatus() == null ? "" : order.captureStatus()) {
                case "COMPLETED" -> result(PaymentProviderResult.Outcome.CAPTURED, order.id(),
                        order.authorizationId(), order.captureId(), null);
                case "DECLINED", "FAILED", "DENIED" -> result(PaymentProviderResult.Outcome.DECLINED,
                        order.id(), order.authorizationId(), order.captureId(), null);
                default -> unknown(request);
            };
        }
        if (order.authorizationId() == null) return unknown(request);
        return switch (order.authorizationStatus() == null ? "" : order.authorizationStatus()) {
            case "CREATED" -> result(PaymentProviderResult.Outcome.AUTHORIZED, order.id(),
                    order.authorizationId(), null, null);
            case "PENDING" -> result(PaymentProviderResult.Outcome.PENDING, order.id(),
                    order.authorizationId(), null, null);
            case "DENIED" -> result(PaymentProviderResult.Outcome.DECLINED, order.id(),
                    order.authorizationId(), null, null);
            case "VOIDED" -> result(PaymentProviderResult.Outcome.VOIDED, order.id(),
                    order.authorizationId(), null, null);
            default -> unknown(request);
        };
    }

    private static boolean isAwaitingPayer(String status) {
        return "CREATED".equals(status) || "SAVED".equals(status) || "PAYER_ACTION_REQUIRED".equals(status);
    }

    private static void validateOrderAmount(PaymentRequest request, PayPalRestClient.OrderStatus order) {
        if (order == null || order.amount() == null
                || order.amount().compareTo(BigDecimal.valueOf(request.amountMinor())) != 0
                || !request.currency().equals(order.currency())) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                    "PayPal order amount or currency does not match the payment attempt");
        }
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
            // Authorization 응답만으로 capture ID를 확정할 수 없어 order GET으로 후속 확인.
            case "CAPTURED", "PARTIALLY_CAPTURED" -> PaymentProviderResult.Outcome.UNKNOWN;
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
