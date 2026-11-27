package com.atlas.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시험이 원장을 비우는 단 하나의 경로. 원장 테이블은 추가 전용 트리거(V7)가 UPDATE·DELETE·TRUNCATE를 거절하므로
 * 한 연결에서만 {@code session_replication_role = replica}로 트리거를 건너뛰고 지운 뒤 되돌린다. 슈퍼유저
 * 연결(Testcontainers 계정)에서만 동작하며 운영 코드는 쓰지 않는다. 자식(항목, 할당)을 먼저 지운다.
 */
public final class LedgerTestReset {
    private LedgerTestReset() {
    }

    public static void clearLedger(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                try {
                    statement.execute("DELETE FROM settlement_entry");
                    statement.execute("DELETE FROM settlement_seller_allocation");
                    statement.execute("DELETE FROM settlement_journal");
                } finally {
                    statement.execute("SET session_replication_role = DEFAULT");
                }
            }
            return null;
        });
    }
}
