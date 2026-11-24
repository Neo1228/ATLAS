package com.atlas.inventorylocation.exception;

import com.atlas.inventorylocation.dto.response.InventoryErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InventoryLocationExceptionHandler {
    @ExceptionHandler(InventoryLocationException.class)
    public ResponseEntity<InventoryErrorResponse> handleInventoryException(InventoryLocationException exception) {
        return ResponseEntity.status(statusFor(exception.code()))
                .body(new InventoryErrorResponse(exception.code().name(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<InventoryErrorResponse> handleInvalidRequest(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(new InventoryErrorResponse("INVALID_REQUEST", "Request validation failed"));
    }

    private HttpStatus statusFor(InventoryLocationException.Code code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_STOCK, IDEMPOTENCY_CONFLICT, INVALID_STATE, RESERVATION_EXPIRED,
                    RESERVATION_CANCELLED -> HttpStatus.CONFLICT;
        };
    }
}
