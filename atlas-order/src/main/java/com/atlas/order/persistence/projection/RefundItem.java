package com.atlas.order.persistence.projection;

import java.util.UUID;

public record RefundItem(UUID orderItemId, int quantity) {
}
