package com.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.catalog.repository.ProductRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 출시한 검색 SQL({@link ProductRepository#searchPublic})을 라벨 질의 64건으로 재생한다(EXP-120).
 *
 * <p>같은 컨테이너에 LC_CTYPE이 {@code en_US.utf8}인 기본 데이터베이스와 {@code C}인 데이터베이스를 두고 V1~V14를 각각 적용한다.
 * pg_trgm처럼 ctype에 따라 한글을 버리는 방식으로 바뀌면 C 쪽의 한국어 recall이 0으로 떨어져 이 시험이 실패한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProductSearchRelevanceIntegrationTest {
    private static final Set<String> KOREAN_KINDS = Set.of("ko", "ko-particle", "ko-attr", "ko-typo");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas").withUsername("atlas").withPassword("atlas");

    private record Label(int queryId, String kind, String query, Set<String> relevant, Set<String> partial) {
    }

    private record Run(String ctype, List<Label> labels, Map<Integer, List<String>> topFive) {
    }

    @Test
    void koreanAndEnglishRecallHoldsAndRankingIsIdenticalUnderUtf8AndCCtype() throws Exception {
        try (Connection admin = connect(POSTGRES.getJdbcUrl())) {
            admin.createStatement().execute(
                    "CREATE DATABASE search_c TEMPLATE template0 LC_CTYPE 'C' LC_COLLATE 'C' ENCODING 'UTF8'");
        }
        Run utf8 = run(POSTGRES.getJdbcUrl());
        Run cType = run(POSTGRES.getJdbcUrl().replace("/atlas", "/search_c"));

        assertThat(utf8.ctype()).isEqualTo("en_US.utf8");
        assertThat(cType.ctype()).isEqualTo("C");
        assertThat(utf8.labels()).hasSize(64);
        assertThat(cType.topFive()).isEqualTo(utf8.topFive());
        for (Run run : List.of(utf8, cType)) {
            Map<String, double[]> metrics = metrics(run);
            System.out.printf("search relevance ctype=%s n=%d recall@5=%.3f nDCG@5=%.3f korean recall@5=%.3f%n",
                    run.ctype(), run.labels().size(), metrics.get("all")[0], metrics.get("all")[1],
                    metrics.get("korean")[0]);
            assertThat(metrics.get("all")[0]).as("recall@5 under %s", run.ctype()).isGreaterThanOrEqualTo(0.90);
            assertThat(metrics.get("korean")[0]).as("Korean recall@5 under %s", run.ctype())
                    .isGreaterThanOrEqualTo(0.85);
            assertThat(metrics.get("ko-particle")[0]).as("particle recall@5 under %s", run.ctype())
                    .isGreaterThanOrEqualTo(0.85);
        }
    }

    private static Run run(String jdbcUrl) throws Exception {
        Flyway.configure().dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connect(jdbcUrl), true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String ctype = jdbc.queryForObject(
                    "SELECT datctype FROM pg_database WHERE datname = current_database()", String.class);
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("search/product-search-relevance.sql"));
            }
            UUID sellerId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            jdbc.update("INSERT INTO seller (seller_id) VALUES (?)", sellerId);
            jdbc.update("INSERT INTO product_category (category_id, seller_id, code, name) VALUES (?, ?, 'all', 'all')",
                    categoryId, sellerId);
            // 키 순서와 UUID 순서를 맞춰 동점 정렬이 실험 표와 같게 한다.
            jdbc.update("""
                    INSERT INTO product (product_id, seller_id, name, description, primary_category_id)
                    SELECT CAST('00000000-0000-0000-0000-' || lpad(substr(product_key, 2), 12, '0') AS uuid),
                           ?, name, description, ?
                    FROM search_fixture_product
                    """, sellerId, categoryId);
            jdbc.update("""
                    INSERT INTO product_channel_availability (product_id, sales_channel, available)
                    SELECT product_id, 'ONLINE_STORE', TRUE FROM product
                    """);
            List<Label> labels = jdbc.query("""
                    SELECT query_id, kind, query, relevant, partial FROM search_fixture_query ORDER BY query_id
                    """, (rs, row) -> new Label(rs.getInt(1), rs.getString(2), rs.getString(3),
                    Set.of((String[]) rs.getArray(4).getArray()), Set.of((String[]) rs.getArray(5).getArray())));
            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
            String sql = ProductRepository.class.getMethod("searchPublic", String.class, Long.class, Long.class,
                    Instant.class, int.class).getAnnotation(Query.class).value();
            Map<Integer, List<String>> topFive = new LinkedHashMap<>();
            for (Label label : labels) {
                MapSqlParameterSource parameters = new MapSqlParameterSource()
                        .addValue("query", label.query()).addValue("minPriceWon", null)
                        .addValue("maxPriceWon", null).addValue("asOf", Timestamp.from(Instant.now()))
                        .addValue("limit", 5);
                topFive.put(label.queryId(), named.query(sql, parameters, (rs, row) ->
                        "p" + rs.getObject("product_id", UUID.class).toString().substring(33)));
            }
            return new Run(ctype, labels, topFive);
        } finally {
            dataSource.destroy();
        }
    }

    /** recall@5 = 상위 5건 중 등급 2 개수 / min(등급 2 수, 5). nDCG@5는 등급 2·1, 이득 2^g - 1. */
    private static Map<String, double[]> metrics(Run run) {
        Map<String, List<double[]>> groups = new HashMap<>();
        for (Label label : run.labels()) {
            List<String> top = run.topFive().getOrDefault(label.queryId(), List.of());
            double hits = top.stream().filter(label.relevant()::contains).count();
            double recall = hits / Math.min(label.relevant().size(), 5);
            double dcg = 0;
            for (int i = 0; i < top.size(); i++) {
                int grade = label.relevant().contains(top.get(i)) ? 2 : label.partial().contains(top.get(i)) ? 1 : 0;
                dcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
            }
            List<Integer> ideal = new ArrayList<>();
            label.relevant().forEach(ignored -> ideal.add(2));
            label.partial().forEach(ignored -> ideal.add(1));
            double idcg = 0;
            for (int i = 0; i < Math.min(5, ideal.size()); i++) {
                idcg += (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
            }
            double[] value = {recall, dcg / idcg};
            groups.computeIfAbsent("all", ignored -> new ArrayList<>()).add(value);
            groups.computeIfAbsent(label.kind(), ignored -> new ArrayList<>()).add(value);
            if (KOREAN_KINDS.contains(label.kind())) {
                groups.computeIfAbsent("korean", ignored -> new ArrayList<>()).add(value);
            }
        }
        Map<String, double[]> means = new HashMap<>();
        groups.forEach((group, values) -> means.put(group, new double[] {
                values.stream().mapToDouble(value -> value[0]).average().orElse(0),
                values.stream().mapToDouble(value -> value[1]).average().orElse(0)}));
        return means;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
