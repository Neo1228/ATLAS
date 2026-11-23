package com.atlas.application.controller.order;

import com.atlas.order.dto.request.OrderInventoryReservationRequest;
import com.atlas.order.dto.response.OrderInventoryReservationResponse;
import com.atlas.order.service.OrderInventoryReservationCoordinator;
import com.atlas.order.persistence.state.OrderInventoryReservationWorkflowState;
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
public class OrderInventoryReservationController {
    private final OrderInventoryReservationCoordinator coordinator;

    public OrderInventoryReservationController(OrderInventoryReservationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping
    public ResponseEntity<OrderInventoryReservationResponse> reserve(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderInventoryReservationRequest request) {
        OrderInventoryReservationResponse response = coordinator.submit(idempotencyKey, request);
        if (response.status().equals(OrderInventoryReservationWorkflowState.RESERVED.name())) {
            return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
        }
        if (response.status().equals(OrderInventoryReservationWorkflowState.FAILED.name())
                || response.status().equals(OrderInventoryReservationWorkflowState.NEEDS_REVIEW.name())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/needs-review")
    public List<OrderInventoryReservationResponse> needsReview(
            @RequestParam(defaultValue = "50") int limit) {
        return coordinator.needsReview(limit);
    }

    /** 운영자가 NEEDS_REVIEW workflow의 원격 재고를 해제(CLAIMED는 복원)하도록 보상을 다시 실행한다. */
    @PostMapping("/{workflowId}/release")
    public OrderInventoryReservationResponse release(@PathVariable UUID workflowId) {
        return coordinator.releaseNeedsReview(workflowId);
    }

    @GetMapping("/{workflowId}")
    public OrderInventoryReservationResponse find(@PathVariable UUID workflowId) {
        return coordinator.find(workflowId);
    }

    @PostMapping("/{workflowId}/reconcile")
    public ResponseEntity<OrderInventoryReservationResponse> reconcile(@PathVariable UUID workflowId) {
        OrderInventoryReservationResponse response = coordinator.retry(workflowId);
        return ResponseEntity.status(response.status().equals(OrderInventoryReservationWorkflowState.RESERVED.name())
                ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{workflowId}/cancel")
    public ResponseEntity<OrderInventoryReservationResponse> cancel(@PathVariable UUID workflowId) {
        OrderInventoryReservationResponse response = coordinator.cancel(workflowId);
        if (response.status().equals(OrderInventoryReservationWorkflowState.CANCELLED.name())
                || response.status().equals(OrderInventoryReservationWorkflowState.FAILED.name())) {
            return ResponseEntity.ok(response);
        }
        if (response.status().equals(OrderInventoryReservationWorkflowState.NEEDS_REVIEW.name())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
