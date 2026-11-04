package com.atlas.inventory.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.persistence.entity.Reservation;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.repository.InventoryPoolRepository;
import com.atlas.inventory.repository.JdbcStockRepository;
import com.atlas.inventory.repository.ReservationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class InventoryServiceImplBatchTest {
    private final InventoryPoolRepository pools = mock(InventoryPoolRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final JdbcStockRepository stock = mock(JdbcStockRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UUID orderId = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private final List<Reservation> stored = new ArrayList<>();
    private InventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(reservations.findLockedByOrderId(orderId)).thenAnswer(call -> new ArrayList<>(stored));
        when(reservations.save(any(Reservation.class))).thenAnswer(call -> {
            Reservation row = call.getArgument(0);
            stored.add(row);
            return row;
        });
        when(reservations.saveAll(ArgumentMatchers.<Iterable<Reservation>>any())).thenAnswer(call -> {
            Iterable<Reservation> rows = call.getArgument(0);
            List<Reservation> saved = new ArrayList<>();
            rows.forEach(saved::add);
            return saved;
        });
        service = new InventoryServiceImpl(pools, reservations, stock, transactions);
    }

    @Test
    void reservesSortedLinesAndClaimsOrReleasesAllRows() {
        when(stock.reserve(seller, "A", 2)).thenReturn(1);
        when(stock.reserve(seller, "B", 3)).thenReturn(1);
        when(stock.claim(seller, "A", 2)).thenReturn(1);
        when(stock.claim(seller, "B", 3)).thenReturn(1);
        when(stock.release(seller, "A", 2)).thenReturn(1);
        when(stock.release(seller, "B", 3)).thenReturn(1);
        var request = new ReserveInventoryBatchRequest(orderId, null, List.of(
                new ReserveInventoryBatchRequest.Item(seller, "B", 3),
                new ReserveInventoryBatchRequest.Item(seller, "A", 2)));

        assertEquals(List.of("A", "B"), service.reserveAll(request).stream().map(r -> r.skuId()).toList());
        InOrder ordered = inOrder(stock);
        ordered.verify(stock).reserve(seller, "A", 2);
        ordered.verify(stock).reserve(seller, "B", 3);
        assertEquals(2, service.claimAll(orderId).size());
        stored.forEach(row -> row.setStatus(ReservationStatus.RESERVED));
        assertEquals(2, service.releaseAll(orderId).size());
        verify(stock).claim(seller, "A", 2);
        verify(stock).release(seller, "B", 3);
    }

    @Test
    void insufficientLaterLineThrowsTypedErrorAndRollsBackTransaction() {
        when(stock.reserve(seller, "A", 2)).thenReturn(1);
        when(stock.reserve(seller, "B", 3)).thenReturn(0);
        var request = new ReserveInventoryBatchRequest(orderId, null, List.of(
                new ReserveInventoryBatchRequest.Item(seller, "A", 2),
                new ReserveInventoryBatchRequest.Item(seller, "B", 3)));

        InventoryException exception = assertThrows(InventoryException.class, () -> service.reserveAll(request));
        assertEquals(InventoryException.Code.INSUFFICIENT_STOCK, exception.code());
        verify(transactions).rollback(any(TransactionStatus.class));
    }

    @Test
    void singleTryReserveReturnsEmptyOnZeroUpdatedRowsWithoutSavingReservation() {
        when(stock.reserve(seller, "SKU", 1)).thenReturn(0);
        var request = new ReserveInventoryRequest(orderId, UUID.randomUUID(), seller, "SKU", 1);

        assertFalse(service.tryReserve(request).isPresent());
        verify(reservations, never()).save(any(Reservation.class));
    }
}
