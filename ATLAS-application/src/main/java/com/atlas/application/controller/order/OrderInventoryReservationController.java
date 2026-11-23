
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