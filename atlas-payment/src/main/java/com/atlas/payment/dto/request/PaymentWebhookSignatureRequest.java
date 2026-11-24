package com.atlas.payment.dto.request;

public record PaymentWebhookSignatureRequest(
        String authAlgorithm,
        String certificateUrl,
        String transmissionId,
        String transmissionSignature,
        String transmissionTime) {
}
