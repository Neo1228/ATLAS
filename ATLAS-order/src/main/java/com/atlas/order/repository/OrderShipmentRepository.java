package com.atlas.order.repository;

import com.atlas.order.persistence.entity.OrderShipment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderShipmentRepository extends JpaRepository<OrderShipment, UUID> {
    Optional<OrderShipment> findByOrderOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);
    List<OrderShipment> findByOrderOrderIdOrderByShippedAtAscShipmentIdAsc(UUID orderId);
}
