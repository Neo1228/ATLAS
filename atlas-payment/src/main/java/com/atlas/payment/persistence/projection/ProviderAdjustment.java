package com.atlas.payment.persistence.projection;

import com.atlas.payment.model.ProviderAdjustmentKind;
import com.atlas.payment.model.ProviderAdjustmentStatus;
import java.util.UUID;

public record ProviderAdjustment(
        UUID adjustmentId,
        ProviderAdjustmentKind kind,
        String providerResourceId,
        UUID attemptId,
        Long amountWon,
        ProviderAdjustmentStatus status,
        String reviewReason) {
}
