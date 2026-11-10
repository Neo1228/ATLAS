package com.atlas.application.exception;

import com.atlas.account.domain.AccountException;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.cart.exception.CartException;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.order.exception.OrderException;
import com.atlas.payment.exception.PaymentException;
import com.atlas.settlement.exception.SettlementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
            AccountException.class,
            CampaignException.class,
            AdmissionException.class,
            CartException.class,
            CatalogException.class,
            InventoryException.class,
            OrderException.class,
            PaymentException.class,
            SettlementException.class
    })
    ResponseEntity<ProblemDetail> moduleException(RuntimeException exception) {
        if (exception instanceof AccountException account) {
            return switch (account.code()) {
                case INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, REFRESH_TOKEN_REUSED, ACCOUNT_DISABLED ->
                        response(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", account.getMessage());
                case EMAIL_ALREADY_USED -> response(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", account.getMessage());
                case RESOURCE_NOT_OWNED -> response(HttpStatus.FORBIDDEN, "FORBIDDEN", account.getMessage());
                case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", account.getMessage());
            };
        }
        if (exception instanceof CampaignException campaign) {
            return switch (campaign.code()) {
                case NOT_FOUND -> response(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", campaign.getMessage());
                case NOT_ELIGIBLE -> response(HttpStatus.CONFLICT, "CONFLICT", campaign.getMessage());
                case INVALID_CONFIGURATION -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", campaign.getMessage());
            };
        }
        if (exception instanceof AdmissionException admission) {
            return switch (admission.code()) {
                case TICKET_NOT_FOUND -> response(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", admission.getMessage());
                case CHECKOUT_SLOT_UNAVAILABLE, IDEMPOTENCY_PAYLOAD_MISMATCH ->
                        response(HttpStatus.CONFLICT, "CONFLICT", admission.getMessage());
                case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", admission.getMessage());
            };
        }

        if (exception instanceof CartException cart) {
            return switch (cart.code()) {
                case NOT_FOUND -> response(HttpStatus.NOT_FOUND, "CART_NOT_FOUND", cart.getMessage());
                case SKU_NOT_FOUND -> response(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND", cart.getMessage());
                case CONFLICT -> response(HttpStatus.CONFLICT, "CONFLICT", cart.getMessage());
                case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", cart.getMessage());
            };
        }
        if (exception instanceof CatalogException catalog) {
            return switch (catalog.code()) {
                case NOT_FOUND -> response(HttpStatus.NOT_FOUND, "CATALOG_RESOURCE_NOT_FOUND", catalog.getMessage());
                case CONFLICT -> response(HttpStatus.CONFLICT, "CONFLICT", catalog.getMessage());
                case PRODUCT_NOT_ORDERABLE -> response(HttpStatus.CONFLICT, "PRODUCT_NOT_ORDERABLE", catalog.getMessage());
                case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", catalog.getMessage());
            };
        }
        if (exception instanceof InventoryException inventory) {
            return switch (inventory.code()) {
 case NOT_FOUND -> response(HttpStatus.NOT_FOUND, "INVENTORY_RESERVATION_NOT_FOUND", inventory.getMessage());
                case LOCATION_NOT_FOUND -> response(HttpStatus.NOT_FOUND, "INVENTORY_LOCATION_NOT_FOUND", inventory.getMessage());
 case POOL_NOT_FOUND -> response(HttpStatus.NOT_FOUND, "INVENTORY_POOL_NOT_FOUND", inventory.getMessage());
                case UNAVAILABLE, CONFLICT -> response(HttpStatus.CONFLICT, "CONFLICT", inventory.getMessage());
                case INSUFFICIENT_STOCK ->
                        response(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", inventory.getMessage());
                case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", inventory.getMessage());
            };
        }
        if (exception instanceof OrderException order) {
            return switch (order.code()) {
                case SHIPMENT_NOT_FOUND -> response(HttpStatus.NOT_FOUND, "ORDER_SHIPMENT_NOT_FOUND", order.getMessage());
                case NOT_FOUND -> response(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", order.getMessage());
                case CONFLICT -> response(HttpStatus.CONFLICT, "CONFLICT", order.getMessage());
                case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", order.getMessage());
            };
        }
        if (exception instanceof PaymentException payment) {
            return switch (payment.code()) {
                case ATTEMPT_NOT_FOUND ->
                        response(HttpStatus.NOT_FOUND, "PAYMENT_ATTEMPT_NOT_FOUND", payment.getMessage());
                case INVALID_REQUEST -> response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", payment.getMessage());
                case PAYMENT_PROVIDER_DISABLED ->
                        response(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_DISABLED", payment.getMessage());
                case PAYMENT_PROVIDER_UNAVAILABLE ->
                        response(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE", payment.getMessage());
            };
        }

        if (exception instanceof SettlementException settlement) {
            return switch (settlement.code()) {
                case INVALID_REQUEST ->
                        response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", settlement.getMessage());
                case CONFLICT -> response(HttpStatus.CONFLICT, "CONFLICT", settlement.getMessage());
            };
        }
        return internalError(exception);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is5xxServerError()) return internalError(exception);
            String code = codeFor(status);
            return response(status, code, null);
        }
        return internalError(exception);
    }

    private ResponseEntity<ProblemDetail> internalError(Exception exception) {
        LOGGER.error("처리되지 않은 HTTP 예외 (requestId={})", MDC.get("requestId"), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null);
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "HTTP_ERROR";
        };
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatusCode status, String code, String ignoredInternalDetail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, koreanDetail(status, code));
        problem.setProperty("code", code);
        String requestId = MDC.get("requestId");
        if (requestId != null) problem.setProperty("requestId", requestId);
        return ResponseEntity.status(status).body(problem);
    }

    private static String koreanDetail(HttpStatusCode status, String code) {
        return switch (code) {
            case "PAYMENT_PROVIDER_DISABLED" -> "현재 결제 서비스가 활성화되어 있지 않습니다.";
            case "PAYMENT_PROVIDER_UNAVAILABLE" -> "결제 서비스에 일시적으로 연결할 수 없습니다. 결제 상태를 확인한 뒤 같은 요청으로 다시 시도해 주세요.";
            case "INSUFFICIENT_STOCK" -> "요청한 수량의 재고를 확보할 수 없습니다.";
            case "PRODUCT_NOT_ORDERABLE" -> "현재 주문할 수 없는 상품입니다.";
            case "INTERNAL_ERROR" -> "서버에서 요청을 처리하지 못했습니다. 요청 ID를 고객 지원에 전달해 주세요.";
            default -> switch (status.value()) {
                case 400 -> "요청 정보를 확인해 주세요.";
                case 401 -> "인증이 필요합니다.";
                case 403 -> "요청을 수행할 권한이 없습니다.";
                case 404 -> "요청한 정보를 찾을 수 없습니다.";
                case 409 -> "현재 상태에서는 요청을 처리할 수 없습니다.";
                case 503 -> "서비스를 일시적으로 사용할 수 없습니다.";
                default -> "요청을 처리할 수 없습니다.";
            };
        };
    }
}
