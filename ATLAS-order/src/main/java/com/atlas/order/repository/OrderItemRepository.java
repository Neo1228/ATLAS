package com.atlas.order.repository;

import com.atlas.order.persistence.entity.OrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
    List<OrderItem> findByOrderOrderIdOrderBySkuIdAsc(UUID orderId);
}
