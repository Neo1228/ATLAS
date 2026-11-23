package com.atlas.application.exception;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * 과부하 중 반복되는 인프라 실패를 요청마다 전체 스택으로 남기지 않도록 한 줄 로그와 표본 스택으로 나눈다.
 *
 * <p>연결 풀이 고갈되면 모든 요청이 같은 예외로 실패하는데, 요청마다 전체 스택 트레이스를 남기면 실패 요청 1건당
 * 약 25KB가 쌓여 이미 실패 중인 앱에 디스크와 CPU 부담을 더한다(EXP-051). 평소에는 요청 ID, 예외 종류, 메시지만
 * 한 줄로 남기고, 원인 분석용 전체 스택은 예외 종류마다 {@code stackInterval}에 한 번만 남긴다.
 */
final class SampledFailureLog {
    private final Logger logger;
    private final Clock clock;
    private final long stackIntervalNanos;
    private final Map<String, Long> lastStackAt = new ConcurrentHashMap<>();

    SampledFailureLog(Logger logger, Clock clock, Duration stackInterval) {
        this.logger = Objects.requireNonNull(logger);
        this.clock = Objects.requireNonNull(clock);
        this.stackIntervalNanos = Objects.requireNonNull(stackInterval).toNanos();
    }

    void warn(String requestId, Exception exception) {
        String type = exception.getClass().getName();
        if (shouldLogStack(type)) {
            logger.warn("용량 부족으로 요청을 거절했습니다 (requestId={}, type={}). 이 종류의 전체 스택은 "
                    + "주기마다 한 번만 남깁니다.", requestId, type, exception);
        } else {
            logger.warn("용량 부족으로 요청을 거절했습니다 (requestId={}, type={}, message={})",
                    requestId, type, exception.getMessage());
        }
    }

    private boolean shouldLogStack(String type) {
        long now = clock.millis() * 1_000_000L;
        boolean[] logStack = {false};
        lastStackAt.compute(type, (key, last) -> {
            if (last == null || now - last >= stackIntervalNanos) {
                logStack[0] = true;
                return now;
            }
            return last;
        });
        return logStack[0];
    }
}
