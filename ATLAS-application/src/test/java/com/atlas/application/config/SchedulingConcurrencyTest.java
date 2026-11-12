package com.atlas.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConcurrencyTest {
    private static final int JOB_COUNT = 3;
    private static final long SERIAL_OBSERVATION_MILLIS = 500;

    @Test
    void applicationSchedulerUsesThreeCoreWorkers() {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfiguration().taskScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void oneWorkerQueuesIndependentJobsUntilTheRunningJobCompletes(TestReporter reporter) throws Exception {
        ScenarioResult result = runScenario(1, reporter);

        assertThat(result.startedBeforeRelease()).isEqualTo(1);
        assertThat(result.maximumConcurrent()).isEqualTo(1);
    }

    @Test
    void threeWorkersStartIndependentJobsWhileAnotherJobIsBlocked(TestReporter reporter) throws Exception {
        ScenarioResult result = runScenario(3, reporter);

        assertThat(result.startedBeforeRelease()).isEqualTo(JOB_COUNT);
        assertThat(result.maximumConcurrent()).isEqualTo(JOB_COUNT);
    }

    private ScenarioResult runScenario(int poolSize, TestReporter reporter) throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduler-probe-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();

        CountDownLatch started = new CountDownLatch(JOB_COUNT);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumConcurrent = new AtomicInteger();
        ConcurrentLinkedQueue<Long> startDelaysMillis = new ConcurrentLinkedQueue<>();

        try {
            for (int job = 0; job < JOB_COUNT; job++) {
                long scheduledAtNanos = System.nanoTime();
                scheduler.schedule(() -> {
                    int running = active.incrementAndGet();
                    maximumConcurrent.accumulateAndGet(running, Math::max);
                    startDelaysMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scheduledAtNanos));
                    started.countDown();
                    firstStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                }, Instant.now());
            }

            assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
            int startedBeforeRelease;
            if (poolSize == 1) {
                assertThat(started.await(SERIAL_OBSERVATION_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
                startedBeforeRelease = JOB_COUNT - (int) started.getCount();
            } else {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                startedBeforeRelease = JOB_COUNT - (int) started.getCount();
            }

            release.countDown();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            ScenarioResult result = new ScenarioResult(
                    startedBeforeRelease, maximumConcurrent.get(), List.copyOf(startDelaysMillis));
            reporter.publishEntry("scheduler.probe.poolSize", Integer.toString(poolSize));
            reporter.publishEntry("scheduler.probe.startedBeforeRelease", Integer.toString(startedBeforeRelease));
            reporter.publishEntry("scheduler.probe.maxConcurrent", Integer.toString(result.maximumConcurrent()));
            reporter.publishEntry("scheduler.probe.startDelayMs", result.startDelaysMillis().toString());
            return result;
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    private record ScenarioResult(int startedBeforeRelease, int maximumConcurrent, List<Long> startDelaysMillis) {
    }
}
