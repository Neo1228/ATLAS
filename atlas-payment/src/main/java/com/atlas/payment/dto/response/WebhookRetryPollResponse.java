package com.atlas.payment.dto.response;

import java.util.List;

/** 한 번의 poll 결과. 처리할 재시도와 이번에 소진 처리한 건수. */
public record WebhookRetryPollResponse(List<ClaimedWebhookRetryResponse> claimed, int reapedCount) {
    public WebhookRetryPollResponse {
        claimed = List.copyOf(claimed);
    }
}
