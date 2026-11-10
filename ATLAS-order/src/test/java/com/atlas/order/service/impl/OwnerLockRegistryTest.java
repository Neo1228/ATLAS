package com.atlas.order.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnerLockRegistryTest {
    @Test
    void serializesOneOwnerWithoutBlockingDifferentOwnerWithSameFormerStripe() throws Exception {
        OwnerLockRegistry locks = new OwnerLockRegistry();
        UUID ownerId = new UUID(0, 0);
        UUID collidingOwnerId = new UUID(0, 256);
        assertThat(Math.floorMod(ownerId.hashCode(), 256))
                .isEqualTo(Math.floorMod(collidingOwnerId.hashCode(), 256));
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch sameOwnerStarted = new CountDownLatch(1);
        CountDownLatch sameOwnerEntered = new CountDownLatch(1);
        CountDownLatch collidingOwnerEntered = new CountDownLatch(1);
        AtomicReference<Thread> sameOwnerThread = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(3)) {
            var ownerTask = executor.submit(() -> locks.withLock(ownerId, () -> {
                ownerEntered.countDown();
                await(releaseOwner);
                return null;
            }));
            Future<?> sameOwnerTask = null;
            Future<?> collidingOwnerTask = null;
            try {
                assertThat(ownerEntered.await(5, TimeUnit.SECONDS)).isTrue();
                sameOwnerTask = executor.submit(() -> {
                    sameOwnerThread.set(Thread.currentThread());
                    sameOwnerStarted.countDown();
                    return locks.withLock(ownerId, () -> {
                        sameOwnerEntered.countDown();
                        return null;
                    });
                });
                assertThat(sameOwnerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlocked(sameOwnerThread.get());
                collidingOwnerTask = executor.submit(() -> locks.withLock(collidingOwnerId, () -> {
                    collidingOwnerEntered.countDown();
                    return null;
                }));
                assertThat(collidingOwnerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                releaseOwner.countDown();
            }
            assertThat(sameOwnerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            ownerTask.get(5, TimeUnit.SECONDS);
            sameOwnerTask.get(5, TimeUnit.SECONDS);
            collidingOwnerTask.get(5, TimeUnit.SECONDS);
        }
        assertThat(locks.registeredKeyCount()).isZero();
    }

    @Test
    void releasesOwnerEntryAfterActionFailure() {
        OwnerLockRegistry locks = new OwnerLockRegistry();
        UUID ownerId = UUID.randomUUID();

        assertThatThrownBy(() -> locks.withLock(ownerId, () -> {
            throw new IllegalStateException("테스트 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(locks.registeredKeyCount()).isZero();
        assertThat(locks.withLock(ownerId, () -> "완료")).isEqualTo("완료");
        assertThat(locks.registeredKeyCount()).isZero();
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("잠금 동기화가 시간 안에 끝나지 않았습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
