package com.atlas.order.repository;

import com.atlas.order.persistence.entity.OrderShipment;
import com.atlas.order.persistence.projection.ShipmentProgress;
import com.atlas.order.persistence.state.OrderShipmentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderShipmentRepository extends JpaRepository<OrderShipment, UUID> {
    Optional<OrderShipment> findByOrderOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    @EntityGraph(attributePaths = {"items", "items.orderItem"})
    @Query("""
            SELECT DISTINCT shipment
            FROM OrderShipment shipment
            WHERE shipment.order.orderId = :orderId
            ORDER BY shipment.shippedAt ASC, shipment.shipmentId ASC
            """)
    List<OrderShipment> findWithItemsByOrderId(@Param("orderId") UUID orderId);

    @Query("""
            SELECT item.orderItem.orderItemId AS orderItemId,
                   SUM(item.quantity) AS shippedQuantity,
                   SUM(CASE WHEN shipment.status = :deliveredStatus
                       THEN item.quantity ELSE 0 END) AS deliveredQuantity
            FROM OrderShipmentItem item
            JOIN item.shipment shipment
            WHERE item.orderId = :orderId
            GROUP BY item.orderItem.orderItemId
            """)
    List<ShipmentProgress> findProgressByOrderId(
            @Param("orderId") UUID orderId, @Param("deliveredStatus") OrderShipmentStatus deliveredStatus);
}
