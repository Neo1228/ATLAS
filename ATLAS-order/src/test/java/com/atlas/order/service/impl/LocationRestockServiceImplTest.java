package com.atlas.order.service.impl;

import com.atlas.order.service.LocationClient;
import com.atlas.order.service.LocationRestockService;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.LocationReservationWorkflowState;
import com.atlas.order.repository.LocationReservationRepository;
import com.atlas.order.persistence.projection.ReservationWorkflowItemRecord;
import com.atlas.order.persistence.projection.ReservationWorkflowLocationSnapshot;
import com.atlas.order.persistence.projection.ReservationWorkflowSnapshot;
import com.atlas.order.repository.LocationRestockRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocationRestockServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Test
    void stagesReturnRestockAtTheShipmentLocationAndItsReservation() {
        LocationReservationRepository workflows = mock(LocationReservationRepository.class);
        LocationRestockRepository restocks = mock(LocationRestockRepository.class);
        LocationClient inventory = mock(LocationClient.class);
        UUID orderId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        ReservationWorkflowSnapshot workflow = workflow(orderId,
                location(locationA, reservationA, sellerId, "sku-a", 2),
                location(locationB, reservationB, sellerId, "sku-a", 1));
        when(workflows.findByWorkflowId(workflowId)).thenReturn(workflow);
        LocationRestockService service = service(workflows, restocks, inventory);

        service.stageAtLocations(refundId, orderId, workflowId, sellerId,
                Map.of(locationA, Map.of("sku-a", 1), locationB, Map.of("sku-a", 1)));

        verify(restocks).insert(LocationRestockServiceImpl.restockKey(refundId), locationA, refundId, orderId,
                workflowId, reservationA, List.of(new ReservationWorkflowItemRecord(sellerId, "sku-a", 1)), NOW);
        verify(restocks).insert(LocationRestockServiceImpl.restockKey(refundId), locationB, refundId, orderId,
                workflowId, reservationB, List.of(new ReservationWorkflowItemRecord(sellerId, "sku-a", 1)), NOW);
    }

    @Test
    void rejectsRestockQuantityAboveTheSourceLocationClaim() {
        LocationReservationRepository workflows = mock(LocationReservationRepository.class);
        LocationRestockRepository restocks = mock(LocationRestockRepository.class);
        LocationClient inventory = mock(LocationClient.class);
        UUID orderId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        ReservationWorkflowSnapshot workflow = workflow(orderId, location(location, UUID.randomUUID(), sellerId, "sku-a", 1));
        when(workflows.findByWorkflowId(workflowId)).thenReturn(workflow);

        assertThatThrownBy(() -> service(workflows, restocks, inventory).stageAtLocations(refundId, orderId,
                workflowId, sellerId, Map.of(location, Map.of("sku-a", 2))))
                .isInstanceOf(OrderException.class);

        verifyNoInteractions(restocks);
    }

    private static LocationRestockService service(LocationReservationRepository workflows,
            LocationRestockRepository restocks, LocationClient inventory) {
        return new LocationRestockServiceImpl(workflows, restocks, inventory, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 4);
    }

    private static ReservationWorkflowSnapshot workflow(UUID orderId, ReservationWorkflowLocationSnapshot... locations) {
        ReservationWorkflowSnapshot workflow = mock(ReservationWorkflowSnapshot.class);
        when(workflow.orderId()).thenReturn(orderId);
        when(workflow.state()).thenReturn(LocationReservationWorkflowState.CLAIMED.name());
        when(workflow.locations()).thenReturn(List.of(locations));
        return workflow;
    }

    private static ReservationWorkflowLocationSnapshot location(UUID locationId, UUID reservationId, UUID sellerId, String skuId,
            int quantity) {
        ReservationWorkflowLocationSnapshot location = mock(ReservationWorkflowLocationSnapshot.class);
        when(location.fulfillmentLocationId()).thenReturn(locationId);
        when(location.reservationId()).thenReturn(reservationId);
        when(location.items()).thenReturn(List.of(new ReservationWorkflowItemRecord(sellerId, skuId, quantity)));
        return location;
    }
}
