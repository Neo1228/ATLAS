package com.atlas.payment.repository;

import com.atlas.payment.provider.PaymentWebhookEvent;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentWebhookEventRepository {
    private final JdbcTemplate jdbc;

    public PaymentWebhookEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertReceived(PaymentWebhookEvent event, Instant receivedAt) {
        return jdbc.update("""
                INSERT INTO payment_provider_webhook_event
                    (event_id, event_type, resource_id, received_at, processing_status)
                VALUES (?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.eventType(), event.resourceId(), Timestamp.from(receivedAt)) == 1;
    }

    public void markProcessed(String eventId, Instant processedAt) {
        int updated = jdbc.update("""
                UPDATE payment_provider_webhook_event
                SET processing_status = 'PROCESSED', processed_at = ?
                WHERE event_id = ? AND processing_status = 'RECEIVED'
                """, Timestamp.from(processedAt), eventId);
        if (updated != 1) throw new IllegalStateException("Webhook event was not in RECEIVED state");
    }
    public void markFailed(String eventId, Instant processedAt) {
        int updated = jdbc.update("""
                UPDATE payment_provider_webhook_event
                SET processing_status = 'FAILED', processed_at = ?
                WHERE event_id = ? AND processing_status = 'RECEIVED'
                """, Timestamp.from(processedAt), eventId);
        if (updated != 1) throw new IllegalStateException("Webhook not RECEIVED: " + eventId);
    }

}
