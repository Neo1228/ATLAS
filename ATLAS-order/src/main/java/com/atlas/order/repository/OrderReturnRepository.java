package com.atlas.order.repository;

import com.atlas.order.persistence.entity.OrderReturn;
import com.atlas.order.persistence.state.OrderReturnStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
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

    @Query("""
            select r from OrderReturn r
            where r.sellerId = :sellerId
                and (:status is null or r.status = :status)
            order by r.requestedAt desc, r.returnId desc
            """)
    List<OrderReturn> findSellerReturns(@Param("sellerId") UUID sellerId,
            @Param("status") OrderReturnStatus status, Pageable pageable);

    @Query("""
            select r from OrderReturn r
            where r.sellerId = :sellerId
                and (:status is null or r.status = :status)
                and (r.requestedAt < :requestedAt
                    or (r.requestedAt = :requestedAt and r.returnId < :returnId))
            order by r.requestedAt desc, r.returnId desc
            """)
    List<OrderReturn> findSellerReturnsBefore(@Param("sellerId") UUID sellerId,
            @Param("status") OrderReturnStatus status, @Param("requestedAt") Instant requestedAt,
            @Param("returnId") UUID returnId, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    List<OrderReturn> findWithItemsByReturnIdIn(List<UUID> returnIds);
    @Query(value = """
            SELECT sh.fulfillment_location_id AS "fulfillmentLocationId",
                   oi.sku_id AS "skuId",
                   ri.quantity AS "quantity"
            FROM order_return_item ri
            JOIN order_shipment sh ON sh.shipment_id = ri.shipment_id
            JOIN order_item oi ON oi.order_item_id = ri.order_item_id
            WHERE ri.refund_id = :refundId
            ORDER BY sh.fulfillment_location_id, oi.sku_id
            """, nativeQuery = true)
    List<ReturnRestockAllocation> findRestockAllocationsByRefundId(@Param("refundId") UUID refundId);

    @Query(value = "SELECT COUNT(*) FROM order_return_item WHERE refund_id = :refundId", nativeQuery = true)
    long countItemsByRefundId(@Param("refundId") UUID refundId);

    interface ReturnRestockAllocation {
        UUID getFulfillmentLocationId();
        String getSkuId();
        Integer getQuantity();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select distinct r from OrderReturn r where r.orderId = :orderId and r.returnId = :returnId")
    Optional<OrderReturn> findWithLockByOrderIdAndReturnId(@Param("orderId") UUID orderId,
                                                           @Param("returnId") UUID returnId);
}
