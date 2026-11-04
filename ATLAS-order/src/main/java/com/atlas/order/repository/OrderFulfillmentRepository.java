package com.atlas.order.repository;

import com.atlas.order.persistence.entity.OrderFulfillment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderFulfillmentRepository extends JpaRepository<OrderFulfillment, UUID> {
}
