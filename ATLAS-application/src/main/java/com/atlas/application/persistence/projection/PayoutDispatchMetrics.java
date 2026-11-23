package com.atlas.application.persistence.projection;

public record PayoutDispatchMetrics(long backlog, long leased, long needsReview, long completed,
        long failed, double oldestAgeSeconds) { }
