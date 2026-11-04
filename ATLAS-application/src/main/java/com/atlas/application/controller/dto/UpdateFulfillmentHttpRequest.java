package com.atlas.application.controller.dto;

import com.atlas.order.persistence.state.FulfillmentStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateFulfillmentHttpRequest(@NotNull FulfillmentStatus status) {
}
