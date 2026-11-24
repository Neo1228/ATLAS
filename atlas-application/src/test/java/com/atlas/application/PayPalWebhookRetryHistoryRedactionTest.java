package com.atlas.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.dto.response.WebhookRetryResponse;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayPalWebhookRetryHistoryRedactionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void operatorResponsesExposeOnlySafeQueueMetadata() throws Exception {
        String pending = mapper.writeValueAsString(new WebhookRetryResponse.PendingRetryPage(1, 0, 20,
                List.of(new WebhookRetryResponse.PendingRetryPage.Item("event-1", "type", 3, null, "AVAILABLE"))));
        String summary = mapper.writeValueAsString(new WebhookRetryResponse.RetrySummary(
                1, 0, null, null, 0, Map.of()));

        assertThat(pending).contains("event-1", "attemptCount")
                .doesNotContain("resourceId", "providerOrderId", "captureId", "resourceAmount",
                        "resourceCurrency", "lastError");
        assertThat(summary).contains("\"pending\":1", "\"exhausted\":0")
                .doesNotContain("eventId", "resource");
    }
}
