package com.atlas.application.controller.dto;

import java.util.List;

public record PayoutDispatchReviewPage(int limit, int offset, boolean hasMore,
        List<PayoutDispatchStatusResponse> items) {
    public PayoutDispatchReviewPage {
        items = List.copyOf(items);
    }
}
