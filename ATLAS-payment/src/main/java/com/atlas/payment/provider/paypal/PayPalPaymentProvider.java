package com.atlas.payment.provider.paypal;

import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.dto.request.PaymentRequest;
import com.atlas.payment.dto.response.PaymentRefundProviderResponse;
import com.atlas.payment.dto.request.PaymentRefundRequest;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import java.math.BigDecimal;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "atlas.payment.provider", havingValue = "paypal")
public class PayPalPaymentProvider implements PaymentProvider {
    /**
     * provider 장애는 결제 서비스에서 UNKNOWN으로 흡수된다. 따라서 이 로그가 없으면
     * 운영자는 "PayPal 장애"와 "PayPal 거절", "base URL 오류"를 구분할 수 없다.
     * 그 구분은 cause 체인에만 남아 있다.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(PayPalPaymentProvider.class);
    private final PayPalRestClient client;

    public PayPalPaymentProvider(PayPalRestClient client) {
        this.client = Objects.requireNonNull(client);
    }
    @Override
    public String providerCurrency() { return "USD"; }

    @Override
    public boolean requiresFxSnapshot() { return true; }

    @Override
    public PaymentProviderResponse perform(PaymentRequest request) {
        try {
            return switch (request.operation()) {
                case AUTHORIZATION -> createOrAuthorize(request);
                case CAPTURE -> capture(request);
                case VOID -> voidAuthorization(request);
            };
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable("perform", exception);
        }
    }

    @Override
    public PaymentProviderResponse completeApproval(PaymentRequest request, String returnedOrderId) {
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
            throw unavailable("complete-approval", exception);
        }
    }


    @Override
    public PaymentRefundProviderResponse refund(PaymentRefundRequest request) {
        try {
            PayPalRestClient.RefundResult refund = client.refundCapture(request.captureId(),
                    BigDecimal.valueOf(request.providerAmountMinor(), 2), request.providerCurrency(),
                    request.requestId());
            return refundResult(refund.id(), refund.status(), refund.amount(), refund.currency());
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable("refund", exception);
        }
    }

    @Override
    public PaymentRefundProviderResponse getRefund(String refundId) {
        try {
            PayPalRestClient.RefundResult refund = client.getRefund(refundId);
            return refundResult(refund.id(), refund.status(), refund.amount(), refund.currency());
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable("refund-lookup", exception);
        }
    }

    @Override
    public PaymentWebhookEventResponse verifyWebhook(PaymentWebhookSignatureRequest signature, String rawBody) {
        try {
            if (!client.verifyWebhook(signature, rawBody).verified()) {
                throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                        "PayPal webhook signature is invalid");
            }
            return client.parseWebhook(rawBody);
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable("verify-webhook", exception);
        }
    }

    private static PaymentRefundProviderResponse refundResult(
            String refundId, String status, BigDecimal amount, String currency) {
        PaymentRefundProviderResponse.Outcome outcome = switch (status == null ? "" : status) {
            case "COMPLETED" -> PaymentRefundProviderResponse.Outcome.COMPLETED;
            case "PENDING", "CREATED" -> PaymentRefundProviderResponse.Outcome.PENDING;
            case "FAILED", "DENIED", "CANCELLED" -> PaymentRefundProviderResponse.Outcome.FAILED;
            default -> PaymentRefundProviderResponse.Outcome.UNKNOWN;
        };
        if (refundId == null && outcome != PaymentRefundProviderResponse.Outcome.FAILED) {
            outcome = PaymentRefundProviderResponse.Outcome.UNKNOWN;
        }
        return new PaymentRefundProviderResponse(outcome, refundId, status, amount, currency);
    }
    private PaymentProviderResponse createOrAuthorize(PaymentRequest request) {
        if (request.providerOrderId() == null) {
            PayPalRestClient.OrderResult order = client.createOrder(request.ownerId().toString(),
                    BigDecimal.valueOf(request.providerAmountMinor(), 2), request.providerCurrency(),
                    request.returnUrl(),
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
                return result(PaymentProviderResponse.Outcome.VOIDED, order.id(), null, null, null);
            }
            if (isAwaitingPayer(status)) {
                return order.approvalUrl() == null ? unknown(withOrder)
                        : result(PaymentProviderResponse.Outcome.PENDING, order.id(), null, null, order.approvalUrl());
            }
            return unknown(withOrder);
        }

        PayPalRestClient.OrderStatus order = client.getOrder(request.providerOrderId());
        validateOrderAmount(request, order);
        return orderStatusResult(request, order);
    }

    private PaymentProviderResponse orderStatusResult(PaymentRequest request, PayPalRestClient.OrderStatus order) {
        if (order.id() == null || !order.id().equals(request.providerOrderId())) return unknown(request);
        String status = order.status() == null ? "" : order.status();
        return switch (status) {
            case "APPROVED" -> authorizeApproved(request);
            case "CREATED", "SAVED", "PAYER_ACTION_REQUIRED" -> pending(request);
            case "VOIDED" -> result(PaymentProviderResponse.Outcome.VOIDED, order.id(),
                    order.authorizationId(), order.captureId(), null);
            case "COMPLETED" -> completedOrderResult(request, order);
            default -> unknown(request);
        };
    }

    private static PaymentProviderResponse completedOrderResult(
            PaymentRequest request, PayPalRestClient.OrderStatus order) {
        if (order.captureId() != null) {
            return switch (order.captureStatus() == null ? "" : order.captureStatus()) {
                case "COMPLETED" -> providerAmountMatches(request, order.captureAmount(), order.captureCurrency())
                        ? result(PaymentProviderResponse.Outcome.CAPTURED, order.id(),
                                order.authorizationId(), order.captureId(), null)
                        : unknown(request);
                case "DECLINED", "FAILED", "DENIED" -> result(PaymentProviderResponse.Outcome.DECLINED,
                        order.id(), order.authorizationId(), order.captureId(), null);
                default -> unknown(request);
            };
        }
        if (order.authorizationId() == null) return unknown(request);
        return switch (order.authorizationStatus() == null ? "" : order.authorizationStatus()) {
            case "CREATED" -> result(PaymentProviderResponse.Outcome.AUTHORIZED, order.id(),
                    order.authorizationId(), null, null);
            case "PENDING" -> result(PaymentProviderResponse.Outcome.PENDING, order.id(),
                    order.authorizationId(), null, null);
            case "DENIED" -> result(PaymentProviderResponse.Outcome.DECLINED, order.id(),
                    order.authorizationId(), null, null);
            case "VOIDED" -> result(PaymentProviderResponse.Outcome.VOIDED, order.id(),
                    order.authorizationId(), null, null);
            default -> unknown(request);
        };
    }

    private static boolean isAwaitingPayer(String status) {
        return "CREATED".equals(status) || "SAVED".equals(status) || "PAYER_ACTION_REQUIRED".equals(status);
    }

    private static boolean providerAmountMatches(
            PaymentRequest request, BigDecimal amount, String currency) {
        return amount != null
                && amount.compareTo(BigDecimal.valueOf(request.providerAmountMinor(), 2)) == 0
                && request.providerCurrency().equalsIgnoreCase(currency);
    }

    private static void validateOrderAmount(PaymentRequest request, PayPalRestClient.OrderStatus order) {
        if (order == null || order.amount() == null
                || order.amount().compareTo(BigDecimal.valueOf(request.providerAmountMinor(), 2)) != 0
                || !request.providerCurrency().equals(order.currency())) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                    "PayPal order amount or currency does not match the payment attempt");
        }
    }

    private PaymentProviderResponse authorizeApproved(PaymentRequest request) {
        PayPalRestClient.AuthorizationResult authorization = client.authorizeOrder(
                request.providerOrderId(), request.authorizationRequestId());
        if (authorization.id() == null) return unknown(request);
        PaymentProviderResponse.Outcome outcome = switch (authorization.status()) {
            case "CREATED" -> PaymentProviderResponse.Outcome.AUTHORIZED;
            case "PENDING" -> PaymentProviderResponse.Outcome.PENDING;
            case "DENIED" -> PaymentProviderResponse.Outcome.DECLINED;
            case "VOIDED" -> PaymentProviderResponse.Outcome.VOIDED;
            // Authorization 응답만으로 capture ID를 확정할 수 없어 order GET으로 후속 확인.
            case "CAPTURED", "PARTIALLY_CAPTURED" -> PaymentProviderResponse.Outcome.UNKNOWN;
            default -> PaymentProviderResponse.Outcome.UNKNOWN;
        };
        return result(outcome, request.providerOrderId(), authorization.id(), null, null);
    }

    private PaymentProviderResponse capture(PaymentRequest request) {
        if (request.providerAuthorizationId() == null) return unknown(request);
        PayPalRestClient.CaptureResult capture = client.captureAuthorization(request.providerAuthorizationId(),
                BigDecimal.valueOf(request.providerAmountMinor(), 2), request.providerCurrency(),
                request.captureRequestId());
        if (!providerAmountMatches(request, capture.amount(), capture.currency())) return unknown(request);
        PaymentProviderResponse.Outcome outcome = "COMPLETED".equals(capture.status())
                ? PaymentProviderResponse.Outcome.CAPTURED
                : "PENDING".equals(capture.status()) ? PaymentProviderResponse.Outcome.PENDING
                : PaymentProviderResponse.Outcome.UNKNOWN;
        return result(outcome, request.providerOrderId(), request.providerAuthorizationId(), capture.id(), null);
    }

    private PaymentProviderResponse voidAuthorization(PaymentRequest request) {
        if (request.providerAuthorizationId() == null) {
            return result(PaymentProviderResponse.Outcome.VOIDED, request.providerOrderId(), null, null, null);
        }
        client.voidAuthorization(request.providerAuthorizationId(), request.voidRequestId());
        return result(PaymentProviderResponse.Outcome.VOIDED, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    private static PaymentRequest withOrder(PaymentRequest request, String orderId) {
        return new PaymentRequest(request.operation(), request.ownerId(), request.attemptId(),
                request.providerAmountMinor(), request.providerCurrency(), orderId,
                request.providerAuthorizationId(), request.createOrderRequestId(),
                request.authorizationRequestId(), request.captureRequestId(), request.voidRequestId(),
                request.returnUrl(), request.cancelUrl());
    }

    private static PaymentProviderResponse pending(PaymentRequest request) {
        return result(PaymentProviderResponse.Outcome.PENDING, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    private static PaymentProviderResponse unknown(PaymentRequest request) {
        return result(PaymentProviderResponse.Outcome.UNKNOWN, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    private static PaymentProviderResponse result(PaymentProviderResponse.Outcome outcome, String orderId,
            String authorizationId, String captureId, String approvalUrl) {
        return new PaymentProviderResponse(outcome, orderId, authorizationId, captureId, approvalUrl);
    }

    private static PaymentException unavailable(String stage, RuntimeException cause) {
        LOGGER.warn("PayPal provider unavailable; stage={} failureType={} causeType={}",
                stage, cause.getClass().getSimpleName(),
                cause.getCause() == null ? "none" : cause.getCause().getClass().getSimpleName());
        PaymentException wrapped = new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                "PayPal payment provider is unavailable");
        wrapped.initCause(cause);
        return wrapped;
    }
}
