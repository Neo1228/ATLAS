import com.atlas.inventory.location.dto.response.InventoryAvailabilityResponse;
    @GetMapping("/availability/{sellerId}/{skuId}")
    public InventoryAvailabilityResponse availableStock(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID sellerId, @PathVariable String skuId) {
        return new InventoryAvailabilityResponse(fulfillmentLocationId, sellerId, skuId,
                reservations.availableStock(fulfillmentLocationId, sellerId, skuId));
    }


    @PostMapping("/reservations/{reservationId}/restore")
    public InventoryReservationResponse restore(@PathVariable UUID fulfillmentLocationId,
            @PathVariable UUID reservationId) {
        return reservations.restore(fulfillmentLocationId, reservationId);
    }