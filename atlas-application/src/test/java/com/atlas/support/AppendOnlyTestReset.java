package com.atlas.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시험이 추가 전용 테이블을 비우는 단 하나의 경로. 원장(V7)과 감사·이벤트 테이블(V11)은 트리거가
 * UPDATE·DELETE·TRUNCATE를 거절하므로 한 연결에서만 {@code session_replication_role = replica}로 트리거를
 * 건너뛰고 지운 뒤 되돌린다. replica 모드는 외래 키 검사도 건너뛰므로 자식 테이블을 먼저 넘긴다. 슈퍼유저
 * 연결(Testcontainers 계정)에서만 동작하며 운영 코드는 쓰지 않는다.
 */
public final class AppendOnlyTestReset {
    private AppendOnlyTestReset() {
    }

    /** 원장 3개 테이블을 자식(항목, 할당)부터 비운다. */
    public static void clearLedger(JdbcTemplate jdbc) {
        clear(jdbc, "settlement_entry", "settlement_seller_allocation", "settlement_journal");
    }

    /** 넘긴 순서대로 각 테이블의 모든 행을 지운다. 부모 행은 지우지 않으므로 자식 테이블만 넘긴다. */
    public static void clear(JdbcTemplate jdbc, String... tables) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                try {
                    for (String table : tables) {
                        statement.execute("DELETE FROM " + table);
                    }
                } finally {
                    statement.execute("SET session_replication_role = DEFAULT");
                }
            }
            return null;
        });
    }
}
