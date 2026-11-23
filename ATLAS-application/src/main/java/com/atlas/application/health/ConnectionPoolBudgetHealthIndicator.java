package com.atlas.application.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 커넥션 풀 크기가 replica 당 허용 몫 안에 있는지 노출합니다.
 *
 * <p>실측 배경: replica당 pool 을 5에서 10으로 올리자 처리량이 61% 늘었지만
 * (127.3 → 205.1 it/s, p95 4,235 → 1,017 ms), 25로 올리자 39.8 it/s 로 무너졌습니다.
 * 원인은 PgBouncer 의 {@code max_db_connections = 52} 였습니다. 클라이언트 125개가
 * 서버 연결 52개를 나눠 쓰며 {@code maxwait} 가 48초까지 올라갔는데, 그때도
 * {@code cl_waiting} 은 0이었습니다. PgBouncer 가 클라이언트 연결을 이미 받아들인 뒤
 * 쿼리 실행 시점에 서버 연결을 기다리기 때문입니다.
 *
 * <p>그래서 이 실패는 조용합니다. Hikari 지표는 정상이고 대기열도 비어 보이며,
 * 증상은 모든 API 가 균일하게 느려지는 형태로만 드러납니다. 이 지표는 그 상태를
 * {@code OUT_OF_SERVICE} 로 만들어 배포 파이프라인과 대시보드가 즉시 알아채게 합니다.
 *
 * <p>풀 크기는 Hikari 구현 타입이 아니라 <b>설정값</b>을 읽습니다. 애플리케이션 모듈이
 * 커넥션 풀 구현에 의존하지 않게 하기 위해서입니다({@code ModuleBoundaryTest} 규칙).
 * 값은 {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE} 환경변수로도 그대로 주입되며,
 * 설정하지 않으면 Hikari 기본값과 같은 10 으로 봅니다.
 *
 * <p>허용 몫은 배포 시 주입합니다({@code ATLAS_POOL_BUDGET_PER_REPLICA}). 앱은 replica 수를
 * 알 수 없으므로 풀 크기와 허용 몫만 비교합니다. 추정으로 총합을 계산하면 검증이
 * 조용히 통과할 수 있습니다. 값이 0 이면 검사하지 않고 {@code UNKNOWN} 을 돌려줍니다.
 */
@Component
public class ConnectionPoolBudgetHealthIndicator implements HealthIndicator {
    static final String BUDGET_PROPERTY = "atlas.datasource.pool-budget-per-replica";
    static final String POOL_SIZE_PROPERTY = "spring.datasource.hikari.maximum-pool-size";

    /** Hikari 의 기본 {@code maximumPoolSize}. */
    static final int HIKARI_DEFAULT_POOL_SIZE = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionPoolBudgetHealthIndicator.class);

    private final int poolSize;
    private final int budgetPerReplica;

    public ConnectionPoolBudgetHealthIndicator(
            @Value("${" + POOL_SIZE_PROPERTY + ":" + HIKARI_DEFAULT_POOL_SIZE + "}") int poolSize,
            @Value("${" + BUDGET_PROPERTY + ":0}") int budgetPerReplica) {
        this.poolSize = poolSize;
        this.budgetPerReplica = budgetPerReplica;
    }

    @Override
    public Health health() {
        if (budgetPerReplica <= 0) {
            return Health.unknown().withDetail("reason", "pool budget not configured").build();
        }
        Health.Builder builder = poolSize > budgetPerReplica ? Health.outOfService() : Health.up();
        builder.withDetail("poolSize", poolSize).withDetail("budgetPerReplica", budgetPerReplica);

        if (poolSize > budgetPerReplica) {
            LOGGER.warn("커넥션 풀이 replica 당 허용 몫을 넘었습니다: pool={} > 허용={}. "
                    + "풀러 서버 연결 상한을 넘기면 모든 요청이 균일하게 느려집니다.",
                    poolSize, budgetPerReplica);
            builder.withDetail("impact", "requests may queue on the pooler server connection limit");
        }
        return builder.build();
    }
}
