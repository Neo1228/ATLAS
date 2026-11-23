package com.atlas.payment.persistence.projection;

import java.time.Instant;

public record TimelineEntry(String category, Instant recordedAt, Integer attemptNumber) {
}
