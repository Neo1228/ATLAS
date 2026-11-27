package com.atlas.settlement.repository;

import com.atlas.settlement.persistence.projection.LedgerAuditFinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 원장 불변식 점검 질의를 실행한다(읽기 전용).
 *
 * <p>질의는 {@code ledger-audit/ledger-audit.sql} 한 파일이 소유한다. 운영자가 같은 파일을 psql로 돌릴 수
 * 있어야 하므로 자바 문자열로 복제하지 않는다. 한 문장이 한 점검이고 세미콜론으로 나눈다.
 */
@Repository
public class SettlementLedgerAuditRepository {
    private static final String AUDIT_RESOURCE = "ledger-audit/ledger-audit.sql";

    private final JdbcTemplate jdbc;
    private final List<String> checks;

    public SettlementLedgerAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.checks = loadChecks();
    }

    public List<LedgerAuditFinding> audit() {
        List<LedgerAuditFinding> findings = new ArrayList<>(checks.size());
        for (String check : checks) {
            findings.add(jdbc.queryForObject(check,
                    (resultSet, rowNumber) -> new LedgerAuditFinding(
                            resultSet.getString("check_name"), resultSet.getLong("violations"))));
        }
        return findings;
    }

    private static List<String> loadChecks() {
        try (var input = new ClassPathResource(AUDIT_RESOURCE).getInputStream()) {
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<String> statements = new ArrayList<>();
            for (String part : script.split(";")) {
                String statement = part.lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .reduce("", (left, right) -> left + right + "\n")
                        .strip();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
            }
            return List.copyOf(statements);
        } catch (IOException exception) {
            throw new UncheckedIOException("Ledger audit script is missing: " + AUDIT_RESOURCE, exception);
        }
    }
}
