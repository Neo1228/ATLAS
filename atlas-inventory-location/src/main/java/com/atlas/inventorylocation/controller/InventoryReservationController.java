package com.atlas.inventorylocation.controller;

import com.atlas.inventorylocation.dto.response.LocationResponse;
import com.atlas.inventorylocation.dto.request.LocationRequest;
import com.atlas.inventorylocation.service.InventoryReservationService;
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
    public LocationResponse.Stock setStock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID sellerId, @PathVariable String skuId, @Valid @RequestBody LocationRequest.Stock request) {
        return reservations.setInitialCapacity(fulfillmentLocationId, sellerId, skuId, request);
    }

    @GetMapping("/stock/{sellerId}/{skuId}")
    public LocationResponse.Stock getStock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID sellerId, @PathVariable String skuId) {
        return reservations.findStock(fulfillmentLocationId, sellerId, skuId);
    }

    @GetMapping("/availability/{sellerId}/{skuId}")
    public LocationResponse.Availability availableStock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID sellerId, @PathVariable String skuId) {
        return new LocationResponse.Availability(fulfillmentLocationId, sellerId, skuId,
                reservations.availableStock(fulfillmentLocationId, sellerId, skuId));
    }

    @PostMapping("/reservations")
    public ResponseEntity<LocationResponse.Reservation> reserve(
            @PathVariable UUID fulfillmentLocationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LocationRequest.Reservation request) {
        LocationResponse.Reservation response = reservations.reserve(
                fulfillmentLocationId, idempotencyKey, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    @PostMapping("/reservations/cancel")
    public LocationResponse.Reservation cancel(
            @PathVariable UUID fulfillmentLocationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LocationRequest.Cancellation request) {
        return reservations.cancel(fulfillmentLocationId, idempotencyKey, request);
    }

    @GetMapping("/reservations/{reservationId}")
    public LocationResponse.Reservation findReservation(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.findReservation(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/claim")
    public LocationResponse.Reservation claim(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.claim(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public LocationResponse.Reservation release(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.release(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/restore")
    public LocationResponse.Reservation restore(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.restore(fulfillmentLocationId, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/restock")
    public LocationResponse.Reservation restock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId, @RequestHeader("Idempotency-Key") String restockKey,
            @Valid @RequestBody LocationRequest.Restock request) {
        return reservations.restock(fulfillmentLocationId, reservationId, restockKey, request.items());
    }
}
