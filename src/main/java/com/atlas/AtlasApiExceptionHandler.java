package com.atlas;

import com.atlas.admission.AdmissionTicketNotFoundException;
import com.atlas.admission.CheckoutSlotUnavailableException;
import com.atlas.admission.IdempotencyPayloadMismatchException;
import com.atlas.campaign.CampaignNotEligibleException;
import com.atlas.campaign.CampaignNotFoundException;
import com.atlas.inventory.InventoryUnavailableException;
import com.atlas.order.OrderConflictException;
import com.atlas.order.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AtlasApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ProblemDetail> orderNotFound(OrderNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    ResponseEntity<ProblemDetail> campaignNotFound(CampaignNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AdmissionTicketNotFoundException.class)
    ResponseEntity<ProblemDetail> ticketNotFound(AdmissionTicketNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({CheckoutSlotUnavailableException.class, IdempotencyPayloadMismatchException.class,
            CampaignNotEligibleException.class, InventoryUnavailableException.class, OrderConflictException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException exception) {
        return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidRequest(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is5xxServerError()) return internalError(exception);
            String detail = status.value() == HttpStatus.BAD_REQUEST.value()
                    ? "Request validation failed" : errorResponse.getBody().getDetail();
            return response(status, codeFor(status), detail == null ? status.toString() : detail);
        }
        return internalError(exception);
    }

    private ResponseEntity<ProblemDetail> internalError(Exception exception) {
        LOGGER.error("Unhandled API exception", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error");
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "HTTP_ERROR";
        };
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
