package com.atlas.inventory.location.controller;

import com.atlas.inventory.location.dto.request.InventoryCancellationRequest;
import com.atlas.inventory.location.dto.request.InventoryReservationRequest;
import com.atlas.inventory.location.dto.request.InventoryRestockRequest;
import com.atlas.inventory.location.dto.request.LocationStockRequest;
import com.atlas.inventory.location.dto.response.InventoryReservationResponse;
import com.atlas.inventory.location.dto.response.InventoryStockResponse;
import com.atlas.inventory.location.dto.response.InventoryAvailabilityResponse;
import com.atlas.inventory.location.service.InventoryReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/fulfillment-locations/{fulfillmentLocationId}")
public class InventoryReservationController {
    private final InventoryReservationService reservations;

    public InventoryReservationController(InventoryReservationService reservations) {
        this.reservations = reservations;
    }

    @PutMapping("/stock/{sellerId}/{skuId}")
    public InventoryStockResponse setStock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID sellerId, @PathVariable String skuId, @Valid @RequestBody LocationStockRequest request) {
        return reservations.setInitialCapacity(fulfillmentLocationId, sellerId, skuId, request);
    }

    @GetMapping("/stock/{sellerId}/{skuId}")
    public InventoryStockResponse getStock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID sellerId, @PathVariable String skuId) {
        return reservations.findStock(fulfillmentLocationId, sellerId, skuId);
    }

    @GetMapping("/availability/{sellerId}/{skuId}")
    public InventoryAvailabilityResponse availableStock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID sellerId, @PathVariable String skuId) {
        return new InventoryAvailabilityResponse(fulfillmentLocationId, sellerId, skuId,
                reservations.availableStock(fulfillmentLocationId, sellerId, skuId));
    }

    @PostMapping("/reservations")
    public ResponseEntity<InventoryReservationResponse> reserve(
            @PathVariable UUID fulfillmentLocationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InventoryReservationRequest request) {
        InventoryReservationResponse response = reservations.reserve(
                fulfillmentLocationId, idempotencyKey, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    @PostMapping("/reservations/cancel")
    public InventoryReservationResponse cancel(
            @PathVariable UUID fulfillmentLocationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InventoryCancellationRequest request) {
        return reservations.cancel(fulfillmentLocationId, idempotencyKey, request);
    }

    @GetMapping("/reservations/{reservationId}")
    public InventoryReservationResponse findReservation(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.findReservation(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/claim")
    public InventoryReservationResponse claim(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.claim(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public InventoryReservationResponse release(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.release(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/restore")
    public InventoryReservationResponse restore(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.restore(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/restock")
    public InventoryReservationResponse restock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId, @RequestHeader("Idempotency-Key") String restockKey,
            @Valid @RequestBody InventoryRestockRequest request) {
        return reservations.restock(fulfillmentLocationId, reservationId, restockKey, request.items());
    }
}
