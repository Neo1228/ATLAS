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
import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;

    public PayPalPaymentProvider(PayPalRestClient client, Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
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
            return refundResult(refund);
        } catch (PayPalRestClient.PayPalUnavailableException exception) {
            throw unavailable("refund", exception);
        }
    }

    @Override
    public PaymentRefundProviderResponse getRefund(String refundId) {
        try {
            PayPalRestClient.RefundResult refund = client.getRefund(refundId);
            return refundResult(refund);
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

    private static PaymentRefundProviderResponse refundResult(PayPalRestClient.RefundResult refund) {
        PaymentRefundProviderResponse.Outcome outcome = switch (refund.status()) {
            case COMPLETED -> PaymentRefundProviderResponse.Outcome.COMPLETED;
            case PENDING, CREATED -> PaymentRefundProviderResponse.Outcome.PENDING;
            case FAILED, DENIED, CANCELLED -> PaymentRefundProviderResponse.Outcome.FAILED;
            case UNKNOWN -> PaymentRefundProviderResponse.Outcome.UNKNOWN;
        };
        if (refund.id() == null && outcome != PaymentRefundProviderResponse.Outcome.FAILED) {
            outcome = PaymentRefundProviderResponse.Outcome.UNKNOWN;
        }
        // 환불 이력에는 enum 이름이 아니라 PayPal이 보낸 원문을 남긴다.
        return new PaymentRefundProviderResponse(outcome, refund.id(), refund.providerStatus(),
                refund.amount(), refund.currency());
    }
    private PaymentProviderResponse createOrAuthorize(PaymentRequest request) {
        if (request.providerOrderId() == null) {
            PayPalRestClient.OrderResult order;
            try {
                order = client.createOrder(request.ownerId().toString(),
                        BigDecimal.valueOf(request.providerAmountMinor(), 2), request.providerCurrency(),
                        request.returnUrl(),
                        request.cancelUrl(), request.createOrderRequestId());
            } catch (PayPalRestClient.PayPalRejectedException rejection) {
                // 400·422 와 PayPal 이슈가 함께 오면 PayPal 이 요청을 검증에서 거절한 것이라 주문이 만들어지지
                // 않았다(같은 Request-Id 재전송도 같은 거절이다). 그 밖의 4xx(401, 403, 429 등)는 인증·한도 같은
                // 일시 조건일 수 있어 결과 불명으로 남긴다.
                if ((rejection.status() != 400 && rejection.status() != 422) || rejection.issue() == null) {
                    throw rejection;
                }
                LOGGER.warn("PayPal rejected order creation definitively; status={} issue={}",
                        rejection.status(), rejection.issue());
                return result(PaymentProviderResponse.Outcome.DECLINED, null, null, null, null);
            }
            if (order.id() == null) return unknown(request);
            PaymentRequest withOrder = withOrder(request, order.id());
            PayPalOrderStatus status = order.status();
            if (status == PayPalOrderStatus.APPROVED || status == PayPalOrderStatus.COMPLETED) {
                PayPalRestClient.OrderStatus current = client.getOrder(order.id());
                validateOrderAmount(withOrder, current);
                return orderStatusResult(withOrder, current);
            }
            if (status == PayPalOrderStatus.VOIDED) {
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
        return switch (order.status()) {
            case APPROVED -> authorizeApproved(request);
            case CREATED, SAVED, PAYER_ACTION_REQUIRED -> pending(request);
            case VOIDED -> result(PaymentProviderResponse.Outcome.VOIDED, order.id(),
                    order.authorizationId(), order.captureId(), null);
            case COMPLETED -> completedOrderResult(request, order);
            case UNKNOWN -> unknown(request);
        };
    }

    private static PaymentProviderResponse completedOrderResult(
            PaymentRequest request, PayPalRestClient.OrderStatus order) {
        if (order.captureId() != null) {
            return switch (order.captureStatus()) {
                case COMPLETED -> providerAmountMatches(request, order.captureAmount(), order.captureCurrency())
                        ? result(PaymentProviderResponse.Outcome.CAPTURED, order.id(),
                                order.authorizationId(), order.captureId(), null)
                        : unknown(request);
                case DECLINED, FAILED, DENIED -> result(PaymentProviderResponse.Outcome.DECLINED,
                        order.id(), order.authorizationId(), order.captureId(), null);
                case PENDING, PARTIALLY_REFUNDED, REFUNDED, UNKNOWN -> unknown(request);
            };
        }
        if (order.authorizationId() == null) return unknown(request);
        return switch (order.authorizationStatus()) {
            case CREATED -> new PaymentProviderResponse(PaymentProviderResponse.Outcome.AUTHORIZED, order.id(),
                    order.authorizationId(), null, null, order.authorizationExpiresAt());
            case PENDING -> result(PaymentProviderResponse.Outcome.PENDING, order.id(),
                    order.authorizationId(), null, null);
            case DENIED -> result(PaymentProviderResponse.Outcome.DECLINED, order.id(),
                    order.authorizationId(), null, null);
            case VOIDED -> result(PaymentProviderResponse.Outcome.VOIDED, order.id(),
                    order.authorizationId(), null, null);
            case CAPTURED, PARTIALLY_CAPTURED, UNKNOWN -> unknown(request);
        };
    }

    private static boolean isAwaitingPayer(PayPalOrderStatus status) {
        return status == PayPalOrderStatus.CREATED || status == PayPalOrderStatus.SAVED
                || status == PayPalOrderStatus.PAYER_ACTION_REQUIRED;
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
            case CREATED -> PaymentProviderResponse.Outcome.AUTHORIZED;
            case PENDING -> PaymentProviderResponse.Outcome.PENDING;
            case DENIED -> PaymentProviderResponse.Outcome.DECLINED;
            case VOIDED -> PaymentProviderResponse.Outcome.VOIDED;
            // Authorization 응답만으로 capture ID를 확정할 수 없어 order GET으로 후속 확인.
            case CAPTURED, PARTIALLY_CAPTURED -> PaymentProviderResponse.Outcome.UNKNOWN;
            case UNKNOWN -> PaymentProviderResponse.Outcome.UNKNOWN;
        };
        return new PaymentProviderResponse(outcome, request.providerOrderId(), authorization.id(), null, null,
                authorization.expiresAt());
    }

    private PaymentProviderResponse capture(PaymentRequest request) {
        if (request.providerAuthorizationId() == null) return unknown(request);
        PayPalRestClient.CaptureResult capture;
        try {
            capture = client.captureAuthorization(request.providerAuthorizationId(),
                    BigDecimal.valueOf(request.providerAmountMinor(), 2), request.providerCurrency(),
                    request.captureRequestId());
        } catch (PayPalRestClient.PayPalRejectedException rejection) {
            return resolveUnusableAuthorization(request, rejection);
        }
        if (!providerAmountMatches(request, capture.amount(), capture.currency())) return unknown(request);
        // capture 요청 응답은 완료와 대기만 확정하고, 거절을 포함한 나머지는 UNKNOWN으로 남긴다.
        PaymentProviderResponse.Outcome outcome = switch (capture.status()) {
            case COMPLETED -> PaymentProviderResponse.Outcome.CAPTURED;
            case PENDING -> PaymentProviderResponse.Outcome.PENDING;
            case DECLINED, PARTIALLY_REFUNDED, REFUNDED, FAILED, DENIED, UNKNOWN ->
                    PaymentProviderResponse.Outcome.UNKNOWN;
        };
        return result(outcome, request.providerOrderId(), request.providerAuthorizationId(), capture.id(), null);
    }

    private PaymentProviderResponse voidAuthorization(PaymentRequest request) {
        if (request.providerAuthorizationId() == null) {
            return result(PaymentProviderResponse.Outcome.VOIDED, request.providerOrderId(), null, null, null);
        }
        try {
            client.voidAuthorization(request.providerAuthorizationId(), request.voidRequestId());
        } catch (PayPalRestClient.PayPalRejectedException rejection) {
            // PREVIOUSLY_VOIDED 는 authorization 이 이미 void 라는 뜻이라 목표 상태에 도달해 있다(EXP-081, Sandbox n=1).
            if (!rejection.is(422, "PREVIOUSLY_VOIDED")) throw rejection;
            LOGGER.info("PayPal authorization was already voided; treating void as done");
        }
        return result(PaymentProviderResponse.Outcome.VOIDED, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    /**
     * capture 가 "authorization 이 void 되었거나 만료됐다"(422 AUTHORIZATION_VOIDED, AUTHORIZATION_EXPIRED)로
     * 거절되면 주문을 조회해 확인한 뒤에만 종결한다. 거절 본문 하나로 "자금이 잡히지 않았다"를 단정하지 않고,
     * capture 가 이미 있으면 그 결과를 따른다(응답이 늦게 반영된 경합). 확인하지 못하면 거절을 그대로 던져
     * 지금처럼 결과 불명(UNKNOWN)이 된다.
     */
    private PaymentProviderResponse resolveUnusableAuthorization(PaymentRequest request,
            PayPalRestClient.PayPalRejectedException rejection) {
        boolean expired = rejection.is(422, "AUTHORIZATION_EXPIRED");
        if ((!expired && !rejection.is(422, "AUTHORIZATION_VOIDED")) || request.providerOrderId() == null) {
            throw rejection;
        }
        PayPalRestClient.OrderStatus current = client.getOrder(request.providerOrderId());
        validateOrderAmount(request, current);
        if (current.id() == null || !current.id().equals(request.providerOrderId())) throw rejection;
        if (current.captureId() != null) return completedOrderResult(request, current);
        // 만료는 PayPal 이 상태를 VOIDED 로 바꾸는지 아직 관측하지 못했다(EXP-086). 만료 거절인데 조회가
        // CREATED 면 조회가 알려 준 만료 시각이 이미 지났을 때만 종결한다. Sandbox 모의 헤더는 거절만 만들고
        // authorization 은 CREATED 에 만료 29일 전으로 두며 이후 capture 도 성공했다(EXP-094).
        boolean unusable = current.authorizationStatus() == PayPalAuthorizationStatus.VOIDED
                || (expired && current.authorizationStatus() == PayPalAuthorizationStatus.CREATED
                && hasExpired(current.authorizationExpiresAt()));
        if (!unusable) throw rejection;
        LOGGER.warn("PayPal authorization is no longer capturable; issue={} authorizationStatus={}",
                rejection.issue(), current.authorizationStatus());
        return result(PaymentProviderResponse.Outcome.VOIDED, request.providerOrderId(),
                request.providerAuthorizationId(), null, null);
    }

    private boolean hasExpired(Instant expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(clock.instant());
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
