package com.atlas.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.order.service.LocationClient;
import com.atlas.order.dto.request.StockClaimRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrecheckStockAdmissionServiceTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void rejectsOnlyWhenConfirmedStockIsShortAndNeverOnUncertainty() {
        LocationClient inventory = mock(LocationClient.class);
        PrecheckStockAdmissionService gate = new PrecheckStockAdmissionService(inventory, Duration.ZERO);
        List<StockClaimRequest> two = List.of(new StockClaimRequest(LOCATION, SELLER, "sku", 2));

        when(inventory.availableStock(any(), any(), any())).thenReturn(1L);
        assertThat(gate.admit("k1", two)).isFalse();

        when(inventory.availableStock(any(), any(), any())).thenReturn(2L);
        assertThat(gate.admit("k2", two)).isTrue();

        // 확인하지 못한 상태는 거절이 아니라 통과다. 최종 판정은 location의 조건부 UPDATE가 한다.
        when(inventory.availableStock(any(), any(), any())).thenThrow(new IllegalStateException("unreachable"));
        assertThat(gate.admit("k3", two)).isTrue();
    }

    @Test
    void confirmedSoldOutIsRememberedSoRepeatedRejectionsMakeNoRemoteCall() {
        LocationClient inventory = mock(LocationClient.class);
        PrecheckStockAdmissionService gate = new PrecheckStockAdmissionService(inventory, Duration.ofMinutes(1));
        List<StockClaimRequest> one = List.of(new StockClaimRequest(LOCATION, SELLER, "sku", 1));
        when(inventory.availableStock(any(), any(), any())).thenReturn(0L);

        for (int i = 0; i < 50; i++) {
            assertThat(gate.admit("k" + i, one)).isFalse();
        }

        // 품절을 한 번 확인한 뒤의 49건은 location을 읽지 않는다.
        verify(inventory, times(1)).availableStock(any(), any(), any());
    }
}
