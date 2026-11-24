package com.atlas.application.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateFulfillmentLocationHttpRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Min(0) Integer priority,
        @NotNull Boolean active) {}
