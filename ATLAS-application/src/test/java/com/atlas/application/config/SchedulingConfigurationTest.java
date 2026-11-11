package com.atlas.application.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConfigurationTest {
    @Test
    void schedulerRunsOverlappingTasksConcurrently() throws InterruptedException {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfiguration().taskScheduler();
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        CountDownLatch secondTaskStarted = new CountDownLatch(1);
        try {
            scheduler.initialize();
            scheduler.schedule(() -> {
                firstTaskStarted.countDown();
                try {
                    releaseFirstTask.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, Instant.now());
            assertTrue(firstTaskStarted.await(2, TimeUnit.SECONDS));

            scheduler.schedule(secondTaskStarted::countDown, Instant.now());

            assertTrue(secondTaskStarted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseFirstTask.countDown();
            scheduler.shutdown();
        }
    }
}
