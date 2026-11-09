package com.atlas.order.persistence.projection;

import java.util.UUID;

public interface ShipmentProgress {
    UUID getOrderItemId();
    Long getShippedQuantity();
    Long getDeliveredQuantity();
}
