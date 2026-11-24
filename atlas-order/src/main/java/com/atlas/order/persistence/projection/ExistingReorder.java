package com.atlas.order.persistence.projection;

import java.util.UUID;

public record ExistingReorder(UUID sourceOrderId, boolean confirmMerge) {}
