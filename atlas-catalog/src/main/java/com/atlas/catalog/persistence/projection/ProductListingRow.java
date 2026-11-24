package com.atlas.catalog.persistence.projection;

import java.util.List;
import java.util.UUID;

/** 목록에 필요한 투영 한 행입니다. */
public record ProductListingRow(UUID productId, List<String> channels, List<UUID> categories) {
}
