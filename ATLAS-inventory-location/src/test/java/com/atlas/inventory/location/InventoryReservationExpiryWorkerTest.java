package com.atlas.inventory.location;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.atlas.inventory.location.service.InventoryReservationExpiryWorker;
import com.atlas.inventory.location.service.InventoryReservationService;
import org.junit.jupiter.api.Test;

class InventoryReservationExpiryWorkerTest {
    @Test
    void repeatedServiceFailureIsContainedForTheNextFixedDelayPoll() {
        InventoryReservationService reservations = mock(InventoryReservationService.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(reservations).expireDue(10);
        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(reservations, 10);

        worker.poll();
        worker.poll();

        verify(reservations, times(2)).expireDue(10);
    }
}
