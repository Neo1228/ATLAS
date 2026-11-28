package com.atlas.application.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AssistantHttpRequest {
    private AssistantHttpRequest() {
    }

    /** {@code confirmedTotalWon}은 구매자가 앱에서 결제 확인을 누른 경우에만 보낸다(ADR-0109). */
    public record Turn(UUID conversationId, @NotBlank @Size(max = 1000) String message,
                       @Positive Long confirmedTotalWon) {
    }

    public record SpendingCap(@Positive @Max(100_000_000L) long capWon) {
    }
}
