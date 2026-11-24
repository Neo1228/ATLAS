package com.atlas.application.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class SampledFailureLogTest {
    @Test
    void repeatedFailuresOfTheSameTypeLogOneStackPerIntervalAndOneLineOtherwise() {
        Logger logger = mock(Logger.class);
        SampledFailureLog log = new SampledFailureLog(
                logger, Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(1));

        for (int index = 0; index < 50; index++) {
            log.warn("req", new IllegalStateException("pool exhausted"));
        }

        verify(logger, times(1)).warn(anyString(), eq("req"), anyString(), any(Throwable.class));
        verify(logger, times(49)).warn(anyString(), eq("req"), anyString(), eq("pool exhausted"));
    }
}
