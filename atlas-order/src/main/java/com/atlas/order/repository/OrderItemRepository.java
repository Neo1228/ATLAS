package com.atlas.order.repository;

import com.atlas.order.persistence.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
    List<OrderItem> findByOrderOrderIdOrderBySkuIdAsc(UUID orderId);

    /**
     * 여러 주문의 item snapshot을 한 번의 조회로 반환한다. 읽기 전용 응답 조립용이므로
     * 호출자가 {@code item.order().orderId()}를 session 밖에서 읽어도 되도록 연관을 즉시 로딩한다.
     */
    @Query("""
            select item from OrderItem item
            join fetch item.order purchaseOrder
            where purchaseOrder.orderId in :orderIds
            order by purchaseOrder.orderId, item.skuId
            """)
    List<OrderItem> findAllByOrderIds(@Param("orderIds") Collection<UUID> orderIds);
}
