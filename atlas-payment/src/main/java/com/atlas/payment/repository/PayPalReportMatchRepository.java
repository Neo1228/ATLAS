package com.atlas.payment.repository;

import com.atlas.payment.persistence.projection.PayPalReportMatchFacts;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PayPalReportMatchRepository {
    private static final String QUERY = """
            SELECT provider_capture_id AS record_id,
                   'CAPTURE' AS match_kind,
                   state = 'CAPTURED' AS confirmed,
                   provider_capture_id AS capture_id,
                   provider_amount_minor,
                   provider_currency
            FROM payment_attempt
            WHERE provider_capture_id IN (:recordIds)
            UNION ALL
            SELECT provider_refund_id AS record_id,
                   'REFUND' AS match_kind,
                   refund_state = 'COMPLETED' AS confirmed,
                   capture_id,
                   provider_amount_minor,
                   provider_currency
            FROM payment_refund_operation
            WHERE provider_refund_id IN (:recordIds)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PayPalReportMatchRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<PayPalReportMatchFacts> findByRecordIds(Collection<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) return List.of();
        LinkedHashSet<String> ids = recordIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        if (ids.isEmpty()) return List.of();
        return jdbc.query(QUERY, new MapSqlParameterSource("recordIds", ids), (resultSet, rowNumber) ->
                new PayPalReportMatchFacts(resultSet.getString("record_id"), resultSet.getString("match_kind"),
                        resultSet.getBoolean("confirmed"), resultSet.getString("capture_id"),
                        resultSet.getObject("provider_amount_minor", Long.class),
                        resultSet.getString("provider_currency")));
    }
}
