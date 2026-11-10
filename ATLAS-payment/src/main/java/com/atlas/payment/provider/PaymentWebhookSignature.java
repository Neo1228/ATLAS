package com.atlas.payment.provider;

public record PaymentWebhookSignature(
        String authAlgorithm,
        String certificateUrl,
        String transmissionId,
        String transmissionSignature,
        String transmissionTime) {
}
