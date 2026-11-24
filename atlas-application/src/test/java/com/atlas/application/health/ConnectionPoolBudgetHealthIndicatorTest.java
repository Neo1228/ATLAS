package com.atlas.application.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class ConnectionPoolBudgetHealthIndicatorTest {

    @Test
    void poolWithinBudgetIsUp() {
        Health health = new ConnectionPoolBudgetHealthIndicator(10, 10).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("poolSize", 10)
                .containsEntry("budgetPerReplica", 10)
                .doesNotContainKey("impact");
    }

    @Test
    void poolBelowBudgetIsUp() {
        Health health = new ConnectionPoolBudgetHealthIndicator(5, 10).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void poolOverBudgetIsOutOfService() {
        Health health = new ConnectionPoolBudgetHealthIndicator(25, 10).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("poolSize", 25)
                .containsEntry("budgetPerReplica", 10)
                .containsEntry("impact", "requests may queue on the pooler server connection limit");
    }

    @Test
    void zeroBudgetDisablesCheck() {
        Health health = new ConnectionPoolBudgetHealthIndicator(100, 0).health();

        // 검사하지 않는 상태를 UP 으로 위장하지 않는다. 미설정은 UNKNOWN 이다.
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("reason", "pool budget not configured");
    }

    @Test
    void defaultPoolSizeMatchesHikari() {
        // 설정이 없을 때 가정하는 값이 실제 풀 기본값과 어긋나면 검사가 무의미해진다.
        assertThat(ConnectionPoolBudgetHealthIndicator.HIKARI_DEFAULT_POOL_SIZE).isEqualTo(10);
    }
}
