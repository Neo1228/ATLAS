package com.atlas.application.controller.dto;

import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record OrderReturnInspectionHttpRequest(@NotEmpty List<@NotNull @Valid Item> items) {
    public record Item(@NotNull UUID orderItemId, @NotNull OrderReturnInspectionRequest.Result result) { }
}
