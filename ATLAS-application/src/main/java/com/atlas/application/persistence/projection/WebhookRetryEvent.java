package com.atlas.application.persistence.projection;

import java.math.BigDecimal;

/** 재시도 큐에 저장하고 읽는 웹훅 이벤트 필드. */
public record WebhookRetryEvent(
        String eventId,
        String eventType,
        String resourceId,
        String resourceStatus,
        String providerOrderId,
        String relatedCaptureId,
        BigDecimal resourceAmount,
        String resourceCurrency) { }
