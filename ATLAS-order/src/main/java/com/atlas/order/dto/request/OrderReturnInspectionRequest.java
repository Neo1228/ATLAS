package com.atlas.order.dto.request;

import java.util.List;
import java.util.UUID;

public record OrderReturnInspectionRequest(List<Item> items) {
    public record Item(UUID orderItemId, Result result) { }
    public enum Result { PASSED, FAILED }
}
