package com.atlas.application.controller.order;

import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.dto.request.LocationReservationRequest;
import com.atlas.order.service.LocationReservationService;
import com.atlas.order.model.LocationReservationWorkflowState;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/order-inventory/reservations")
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.enabled", havingValue = "true")
public class LocationReservationController {
    private final LocationReservationService coordinator;

    public LocationReservationController(LocationReservationService coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping
    public ResponseEntity<ReservationWorkflowResponse.LocationReservation> reserve(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LocationReservationRequest request) {
        ReservationWorkflowResponse.LocationReservation response = coordinator.submit(idempotencyKey, request);
        if (response.status() == LocationReservationWorkflowState.RESERVED) {
            return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
        }
        if (response.status() == LocationReservationWorkflowState.FAILED
                || response.status() == LocationReservationWorkflowState.NEEDS_REVIEW) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/needs-review")
    public List<ReservationWorkflowResponse.LocationReservation> needsReview(
            @RequestParam(defaultValue = "50") int limit) {
        return coordinator.needsReview(limit);
    }

    /** 운영자가 NEEDS_REVIEW workflow의 원격 재고를 해제(CLAIMED는 복원)하도록 보상을 다시 실행한다. */
    @PostMapping("/{workflowId}/release")
    public ReservationWorkflowResponse.LocationReservation release(@PathVariable UUID workflowId) {
        return coordinator.releaseNeedsReview(workflowId);
    }

    @GetMapping("/{workflowId}")
    public ReservationWorkflowResponse.LocationReservation find(@PathVariable UUID workflowId) {
        return coordinator.find(workflowId);
    }

    @PostMapping("/{workflowId}/reconcile")
    public ResponseEntity<ReservationWorkflowResponse.LocationReservation> reconcile(@PathVariable UUID workflowId) {
        ReservationWorkflowResponse.LocationReservation response = coordinator.retry(workflowId);
        return ResponseEntity.status(response.status() == LocationReservationWorkflowState.RESERVED
                ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{workflowId}/cancel")
    public ResponseEntity<ReservationWorkflowResponse.LocationReservation> cancel(@PathVariable UUID workflowId) {
        ReservationWorkflowResponse.LocationReservation response = coordinator.cancel(workflowId);
        if (response.status() == LocationReservationWorkflowState.CANCELLED
                || response.status() == LocationReservationWorkflowState.FAILED) {
            return ResponseEntity.ok(response);
        }
        if (response.status() == LocationReservationWorkflowState.NEEDS_REVIEW) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
