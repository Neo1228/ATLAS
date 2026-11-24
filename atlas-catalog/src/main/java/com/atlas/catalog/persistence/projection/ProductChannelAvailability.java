package com.atlas.catalog.persistence.projection;

import java.util.UUID;

public interface ProductChannelAvailability {
    UUID getProductId();
    String getSalesChannel();
}
