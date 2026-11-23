package com.atlas.application.persistence.projection;

import java.util.List;

public record PollResult(List<ClaimedRetry> claimed, int reapedCount) { }
