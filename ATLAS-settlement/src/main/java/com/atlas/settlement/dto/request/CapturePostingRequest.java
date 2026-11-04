package com.atlas.settlement.dto.request;

import java.util.UUID;

public record CapturePostingRequest(UUID orderId, UUID attemptId, UUID sellerId, long grossAmountWon) {
}
