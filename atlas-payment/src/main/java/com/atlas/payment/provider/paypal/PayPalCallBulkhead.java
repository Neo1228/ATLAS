package com.atlas.payment.provider.paypal;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 요청 스레드가 PayPal 응답을 기다리며 동시에 묶일 수 있는 호출 수의 상한(bulkhead).
 * PayPal 이 느려지면 호출당 묶이는 시간이 read timeout 까지 늘고, 묶이는 스레드 수는 도착률 × 지연이라
 * Tomcat 스레드(기본 40)를 모두 채우면 PayPal 을 쓰지 않는 요청까지 밀린다(EXP-108).
 * 상한을 넘는 요청 스레드의 호출은 아무것도 보내기 전에 기다리지 않고 거절한다.
 * <p>복구 worker 같은 서블릿 요청 밖의 스레드는 세지도 거절하지도 않는다: worker 가 거절되면 복구가 멈춘다.
 * 요청 스레드 여부는 {@link RequestContextHolder} 로 구분하며 서블릿 요청을 처리하는 동안에만 값이 있다.
 * <p>결제 모듈은 지표 라이브러리에 의존하지 않는다. 거절 수는 조립하는 쪽(application)이 넘긴 훅으로 알린다.
 */
public class PayPalCallBulkhead {
    private final Semaphore permits;
    private final Runnable onRejected;

    /** {@code maxConcurrentCalls} 가 0 이하이면 상한이 없다(이전 동작). */
    public PayPalCallBulkhead(int maxConcurrentCalls, Runnable onRejected) {
        this.permits = maxConcurrentCalls > 0 ? new Semaphore(maxConcurrentCalls) : null;
        this.onRejected = Objects.requireNonNull(onRejected);
    }

    <T> T call(Supplier<T> action) {
        if (permits == null || RequestContextHolder.getRequestAttributes() == null) return action.get();
        if (!permits.tryAcquire()) {
            onRejected.run();
            throw new PayPalRestClient.PayPalBusyException("PayPal in-flight call limit reached");
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }
}
