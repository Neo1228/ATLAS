package com.atlas.application.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

public record OrderRefundHttpRequest(@NotEmpty List<@Valid Item> items) {
    public record Item(@NotNull UUID orderItemId, @Positive int quantity) {
    }
}
