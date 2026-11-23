package com.atlas.settlement.service;

public record PayoutDispatchMetrics(long backlog, long leased, long needsReview, long completed,
        long failed, double oldestAgeSeconds) { }
