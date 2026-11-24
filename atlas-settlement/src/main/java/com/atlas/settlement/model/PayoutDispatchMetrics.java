package com.atlas.settlement.model;

public record PayoutDispatchMetrics(long backlog, long leased, long needsReview, long completed,
        long failed, double oldestAgeSeconds) { }
