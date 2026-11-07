package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdjustInventoryCapacityHttpRequest(int delta, @NotBlank @Size(max = 256) String reason) {}
