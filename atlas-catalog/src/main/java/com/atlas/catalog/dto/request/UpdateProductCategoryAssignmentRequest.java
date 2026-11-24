package com.atlas.catalog.dto.request;

import java.util.List;
import java.util.UUID;

public record UpdateProductCategoryAssignmentRequest(List<UUID> categoryIds) {
}