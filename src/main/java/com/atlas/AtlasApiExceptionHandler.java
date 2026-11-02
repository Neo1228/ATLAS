package com.atlas;

import com.atlas.admission.CheckoutSlotUnavailableException;
import com.atlas.admission.IdempotencyPayloadMismatchException;
import com.atlas.campaign.CampaignNotEligibleException;
import com.atlas.inventory.InventoryUnavailableException;
import com.atlas.order.OrderConflictException;
import com.atlas.order.OrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AtlasApiExceptionHandler {

    public record ApiError(String code, String message) { }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiError> orderNotFound(OrderNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({CheckoutSlotUnavailableException.class, IdempotencyPayloadMismatchException.class,
            CampaignNotEligibleException.class, InventoryUnavailableException.class, OrderConflictException.class})
    ResponseEntity<ApiError> conflict(RuntimeException exception) {
        return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidRequest(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    private static ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
