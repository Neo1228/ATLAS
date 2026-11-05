package com.atlas.payment.repository;

import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.provider.fake.FakePsp;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FakePspRepository {
    private final JdbcTemplate jdbc;

    public FakePspRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(PaymentPhase phase, List<FakePsp.Result> results) {
        jdbc.execute((Connection connection) -> {
            Array additions = connection.createArrayOf("text", results.stream().map(Enum::name).toArray());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO payment_fake_psp_script(payment_phase, queued_results)
                    VALUES (?, ?)
                    ON CONFLICT (payment_phase) DO UPDATE
                    SET queued_results = payment_fake_psp_script.queued_results || EXCLUDED.queued_results
                    """)) {
                statement.setString(1, phase.name());
                statement.setArray(2, additions);
                statement.executeUpdate();
            } finally {
                additions.free();
            }
            return null;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FakePsp.Result perform(PaymentPhase phase, String requestId, String idempotencyKey,
                                  FakePsp.Result defaultResult) {
        return jdbc.execute((Connection connection) -> {
            boolean created;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO payment_fake_psp_state(payment_phase, request_id, current_result)
                    VALUES (?, ?, ?)
                    ON CONFLICT (payment_phase, request_id) DO NOTHING
                    """)) {
                insert.setString(1, phase.name());
                insert.setString(2, requestId);
                insert.setString(3, defaultResult.name());
                created = insert.executeUpdate() == 1;
            }

            String current;
            List<String> queued;
            try (var select = connection.prepareStatement("""
                    SELECT current_result, queued_results FROM payment_fake_psp_state
                    WHERE payment_phase = ? AND request_id = ? FOR UPDATE
                    """)) {
                select.setString(1, phase.name());
                select.setString(2, requestId);
                try (var rows = select.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Fake PSP state row was not created");
                    current = rows.getString(1);
                    queued = arrayValues(rows.getArray(2));
                }
            }
            try (var select = connection.prepareStatement("""
                    SELECT result FROM payment_fake_psp_operation
                    WHERE payment_phase = ? AND request_id = ? AND idempotency_key = ?
                    """)) {
                select.setString(1, phase.name());
                select.setString(2, requestId);
                select.setString(3, idempotencyKey);
                try (var rows = select.executeQuery()) {
                    if (rows.next()) return FakePsp.Result.valueOf(rows.getString(1));
                }
            }

            if (created) {
                List<String> script;
                try (var select = connection.prepareStatement("""
                        SELECT queued_results FROM payment_fake_psp_script
                        WHERE payment_phase = ? FOR UPDATE
                        """)) {
                    select.setString(1, phase.name());
                    try (var rows = select.executeQuery()) {
                        script = rows.next() ? arrayValues(rows.getArray(1)) : new ArrayList<>();
                    }
                }
                if (!script.isEmpty()) {
                    current = script.remove(0);
                    queued = script;
                    try (var clear = connection.prepareStatement(
                            "UPDATE payment_fake_psp_script SET queued_results = '{}' WHERE payment_phase = ?")) {
                        clear.setString(1, phase.name());
                        clear.executeUpdate();
                    }
                }
                updateState(connection, phase, requestId, current, queued);
            }

            boolean hasPriorOperation;
            try (var select = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM payment_fake_psp_operation
                    WHERE payment_phase = ? AND request_id = ?)
                    """)) {
                select.setString(1, phase.name());
                select.setString(2, requestId);
                try (var rows = select.executeQuery()) {
                    rows.next();
                    hasPriorOperation = rows.getBoolean(1);
                }
            }
            if (hasPriorOperation
                    && (current.equals(FakePsp.Result.PENDING.name()) || current.equals(FakePsp.Result.UNKNOWN.name()))
                    && !queued.isEmpty()) {
                current = queued.remove(0);
                updateState(connection, phase, requestId, current, queued);
            }

            try (var insert = connection.prepareStatement("""
                    INSERT INTO payment_fake_psp_operation(payment_phase, request_id, idempotency_key, result)
                    VALUES (?, ?, ?, ?)
                    """)) {
                insert.setString(1, phase.name());
                insert.setString(2, requestId);
                insert.setString(3, idempotencyKey);
                insert.setString(4, current);
                insert.executeUpdate();
            }
            return FakePsp.Result.valueOf(current);
        });
    }

    private static void updateState(Connection connection, PaymentPhase phase, String requestId,
                                    String current, List<String> queued) throws SQLException {
        Array array = connection.createArrayOf("text", queued.toArray());
        try (var update = connection.prepareStatement("""
                UPDATE payment_fake_psp_state SET current_result = ?, queued_results = ?
                WHERE payment_phase = ? AND request_id = ?
                """)) {
            update.setString(1, current);
            update.setArray(2, array);
            update.setString(3, phase.name());
            update.setString(4, requestId);
            update.executeUpdate();
        } finally {
            array.free();
        }
    }

    private static List<String> arrayValues(Array array) throws SQLException {
        if (array == null) return new ArrayList<>();
        try {
            Object value = array.getArray();
            if (value instanceof String[] strings) return new ArrayList<>(List.of(strings));
            Object[] values = (Object[]) value;
            List<String> result = new ArrayList<>(values.length);
            for (Object item : values) result.add(item.toString());
            return result;
        } finally {
            array.free();
        }
    }
}
