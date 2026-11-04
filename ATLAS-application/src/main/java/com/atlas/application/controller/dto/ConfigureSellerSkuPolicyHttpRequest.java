package com.atlas.application.controller.dto;

import jakarta.validation.constraints.Positive;

public record ConfigureSellerSkuPolicyHttpRequest(@Positive int maxActiveCheckouts) {
}
