package com.atlas.application.service.impl;

import com.atlas.order.exception.LocationClientException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 요청 스레드가 위치 서비스 응답을 기다리며 동시에 묶일 수 있는 호출 수의 상한(EXP-109).
 * 위치 서비스가 느려지면 호출당 묶이는 시간이 읽기 상한(기본 2 s)까지 늘고, 묶이는 스레드 수는 도착률 × 지연이라
 * Tomcat 스레드(기본 40)를 모두 채우면 위치 서비스를 쓰지 않는 요청까지 밀린다.
 * 상한을 넘는 요청 스레드의 호출은 아무것도 보내기 전에 기다리지 않고 {@code RETRYABLE_UNKNOWN}으로 거절한다:
 * 호출 쪽이 시간 초과를 받았을 때와 같은 경로(같은 멱등 키로 다시 시도)로 수렴한다.
 * 서블릿 요청 밖의 스레드(복구 worker)는 세지도 거절하지도 않는다.
 */
class LocationCallBulkhead {
    private final Semaphore permits;
    private final Runnable onRejected;

    /** {@code maxConcurrentCalls} 가 0 이하이면 상한이 없다(이전 동작). */
    LocationCallBulkhead(int maxConcurrentCalls, Runnable onRejected) {
        this.permits = maxConcurrentCalls > 0 ? new Semaphore(maxConcurrentCalls) : null;
        this.onRejected = Objects.requireNonNull(onRejected);
    }

    <T> T call(Supplier<T> action) {
        if (permits == null || RequestContextHolder.getRequestAttributes() == null) return action.get();
        if (!permits.tryAcquire()) {
            onRejected.run();
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_BUSY", "Inventory in-flight call limit reached");
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }
}
