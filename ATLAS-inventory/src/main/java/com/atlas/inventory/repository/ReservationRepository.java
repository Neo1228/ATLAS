package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.entity.Reservation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from Reservation reservation where reservation.orderId = :orderId order by reservation.sellerId, reservation.skuId")
    List<Reservation> findLockedByOrderId(@Param("orderId") UUID orderId);

    List<Reservation> findAllByOrderIdOrderBySellerIdAscSkuIdAsc(UUID orderId);
}
