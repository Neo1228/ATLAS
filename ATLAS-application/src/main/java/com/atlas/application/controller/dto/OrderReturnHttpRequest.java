package com.atlas.application.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

public record OrderReturnHttpRequest(@NotEmpty List<@NotNull @Valid Item> items) {
    /** 분할 출고 반품은 출처 shipment를 지정하며, 생략은 유일한 출처가 확인될 때만 허용된다. */
    public record Item(@NotNull UUID orderItemId, @Positive int quantity, UUID shipmentId) {
        public Item(UUID orderItemId, int quantity) {
            this(orderItemId, quantity, null);
        }
    }
}
