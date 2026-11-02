package com.atlas.inventory;

import java.util.UUID;

public interface InventoryApi {

    void createPool(CreateInventoryPoolCommand command);

    ReservationView reserve(ReserveInventoryCommand command);

    ReservationView claim(UUID orderId);
    java.util.Optional<ReservationView> tryReserve(ReserveInventoryCommand command);
    ReservationView findReservation(UUID orderId);

    ReservationView release(UUID orderId);

    InventoryView find(UUID campaignId, String skuId);
}
