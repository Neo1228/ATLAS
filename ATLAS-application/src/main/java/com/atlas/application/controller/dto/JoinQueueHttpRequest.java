package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinQueueHttpRequest(
        @NotBlank @Size(max = 128) String skuId,
        @NotBlank @Size(max = 256) String buyerRef) {
}
