package com.atlas.order.repository;

import com.atlas.order.persistence.entity.OrderReturn;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {
    @EntityGraph(attributePaths = "items")
    Optional<OrderReturn> findWithItemsByReturnId(UUID returnId);

    @EntityGraph(attributePaths = "items")
    Optional<OrderReturn> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    @EntityGraph(attributePaths = "items")
    List<OrderReturn> findAllByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select distinct r from OrderReturn r where r.orderId = :orderId and r.returnId = :returnId")
    Optional<OrderReturn> findWithLockByOrderIdAndReturnId(@Param("orderId") UUID orderId,
                                                           @Param("returnId") UUID returnId);
}
