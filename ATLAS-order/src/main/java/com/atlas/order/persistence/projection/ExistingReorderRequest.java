package com.atlas.order.persistence.projection;

import java.util.UUID;

public record ExistingReorderRequest(UUID sourceOrderId, boolean confirmMerge) {}
