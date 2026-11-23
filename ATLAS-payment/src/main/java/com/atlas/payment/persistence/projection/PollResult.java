package com.atlas.payment.persistence.projection;

import java.util.List;

public record PollResult(List<ClaimedRetry> claimed, int reapedCount) { }
