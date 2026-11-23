package com.atlas.application.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 배송 완료 구매 상품에 남길 buyer review 요청입니다. */
public record CreateProductReviewRequest(
        @NotNull UUID productId,
        @Min(1) @Max(5) int rating,
        @NotBlank @Size(max = 2000) String body) {
}
