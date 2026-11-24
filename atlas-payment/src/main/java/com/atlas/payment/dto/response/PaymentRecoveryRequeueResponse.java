package com.atlas.payment.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 운영자 수동 재큐 결과. {@code duplicate}는 같은 멱등 키의 이전 요청을 재생한 경우다. */
public record PaymentRecoveryRequeueResponse(
        UUID recoveryJobId,
        String status,
        int manualRequeueCount,
        Instant acceptedAt,
        boolean duplicate) {
}
