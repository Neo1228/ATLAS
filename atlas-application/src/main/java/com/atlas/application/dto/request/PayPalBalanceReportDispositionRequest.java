package com.atlas.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 대사 행에 기록할 운영자 disposition 요청입니다. */
public record PayPalBalanceReportDispositionRequest(
        @NotBlank @Size(max = 32) String disposition,
        @NotBlank @Size(max = 500) String reason) {
}
