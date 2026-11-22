package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Toxiproxy를 통과하는 실제 PostgreSQL transaction의 commit 결과를 검증한다. */
class SettlementToxiproxyCommitBoundaryIntegrationTest {
    private static final int TOXIPROXY_API_PORT = 8474;
    private static final int POSTGRES_PROXY_PORT = 8666;
    private static final Duration OBSERVER_TIMEOUT = Duration.ofSeconds(8);
    private static final Network NETWORK = Network.newNetwork();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static boolean networkClosed;

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("postgres:18.6"))
            .withDatabaseName("settlement")
            .withUsername("settlement")
            .withPassword("settlement")
            .withNetwork(NETWORK)
            .withNetworkAliases("settlement-postgres");

    private static final GenericContainer<?> TOXIPROXY = new GenericContainer<>(
                    DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK)
            .withExposedPorts(TOXIPROXY_API_PORT, POSTGRES_PROXY_PORT);

    @BeforeAll
    static void configureDatabaseAndProxy() throws Exception {
        try {
            POSTGRES.start();
            TOXIPROXY.start();
            awaitToxiproxyApiReady();
            try (Connection direct = directConnection(); Statement statement = direct.createStatement()) {
                statement.execute("CREATE TABLE commit_probe_parent ("
                        + "id UUID PRIMARY KEY, request_key UUID NOT NULL UNIQUE)");
                statement.execute("CREATE TABLE commit_probe_child ("
                        + "id UUID PRIMARY KEY, parent_id UUID NOT NULL UNIQUE "
                        + "REFERENCES commit_probe_parent(id), payload TEXT NOT NULL)");
            }
            api("POST", "/proxies", "{\"name\":\"postgres\",\"listen\":\"0.0.0.0:"
                    + POSTGRES_PROXY_PORT + "\",\"upstream\":\"settlement-postgres:5432\",\"enabled\":true}");
        } catch (Exception | Error failure) {
            stopContainersAndNetwork();
            throw failure;
        }
    }

    @AfterAll
    static void closeNetwork() {
        stopContainersAndNetwork();
    }

    private static void stopContainersAndNetwork() {
        try {
            if (TOXIPROXY.isRunning()) {
                TOXIPROXY.stop();
            }
        } finally {
            try {
                if (POSTGRES.isRunning()) {
                    POSTGRES.stop();
                }
            } finally {
                if (!networkClosed) {
                    NETWORK.close();
                    networkClosed = true;
                }
            }
        }
    }

    @Test
    void downstreamLatencyTimesOutCommitAcknowledgmentButRetryRemainsIdempotent() throws Exception {
        UUID requestKey = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        try (Connection connection = proxyConnection("toxiproxy-commit-" + UUID.randomUUID())) {
            connection.setAutoCommit(false);
            insertRows(connection, requestKey, parentId, childId, "first attempt");

            // 모든 SQL write가 성공한 뒤 COMMIT 직전에만 toxic을 추가한다.
            addToxic("commit_ack_delay", "latency", "downstream",
                    "\"latency\":10000,\"jitter\":0");
            try {
                assertThatThrownBy(connection::commit)
                        .as("the client must stop waiting before the delayed COMMIT acknowledgment arrives")
                        .isInstanceOf(SQLException.class);
            } finally {
                deleteToxic("commit_ack_delay");
            }
        }

        awaitCondition(() -> parentCount(requestKey) == 1 && childCount(requestKey) == 1,
                "PostgreSQL did not expose both rows committed before the acknowledgment timeout");
        retryIdempotently(requestKey, parentId, childId);
        assertThat(parentCount(requestKey)).isEqualTo(1);
        assertThat(childCount(requestKey)).isEqualTo(1);
    }

    @Test
    void upstreamResetBeforeCommitRollsBackParentAndChild() throws Exception {
        UUID requestKey = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        String applicationName = "toxiproxy-reset-" + UUID.randomUUID();

        try (Connection connection = proxyConnection(applicationName)) {
            connection.setAutoCommit(false);
            insertRows(connection, requestKey, parentId, childId, "must roll back");
            addToxic("commit_reset", "reset_peer", "upstream", "\"timeout\":0");
            try {
                assertThatThrownBy(connection::commit)
                        .as("upstream reset must interrupt COMMIT")
                        .isInstanceOf(SQLException.class);
            } finally {
                deleteToxic("commit_reset");
            }
        }

        awaitCondition(() -> !backendExists(applicationName),
                "the reset PostgreSQL backend did not terminate within the bounded wait");
        assertThat(parentCount(requestKey)).isZero();
        assertThat(childCount(requestKey)).isZero();
    }

    private static void insertRows(Connection connection, UUID requestKey, UUID parentId, UUID childId, String payload)
            throws SQLException {
        try (PreparedStatement parent = connection.prepareStatement(
                        "INSERT INTO commit_probe_parent(id, request_key) VALUES (?, ?)")) {
            parent.setObject(1, parentId);
            parent.setObject(2, requestKey);
            parent.executeUpdate();
        }
        try (PreparedStatement child = connection.prepareStatement(
                        "INSERT INTO commit_probe_child(id, parent_id, payload) VALUES (?, ?, ?)")) {
            child.setObject(1, childId);
            child.setObject(2, parentId);
            child.setString(3, payload);
            child.executeUpdate();
        }
    }

    private static void retryIdempotently(UUID requestKey, UUID parentId, UUID childId) throws SQLException {
        try (Connection connection = directConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement parent = connection.prepareStatement(
                    "INSERT INTO commit_probe_parent(id, request_key) VALUES (?, ?) "
                            + "ON CONFLICT (request_key) DO NOTHING")) {
                parent.setObject(1, parentId);
                parent.setObject(2, requestKey);
                parent.executeUpdate();
            }
            UUID persistedParent = queryParentId(connection, requestKey);
            try (PreparedStatement child = connection.prepareStatement(
                    "INSERT INTO commit_probe_child(id, parent_id, payload) VALUES (?, ?, ?) "
                            + "ON CONFLICT (parent_id) DO NOTHING")) {
                child.setObject(1, childId);
                child.setObject(2, persistedParent);
                child.setString(3, "idempotent retry");
                child.executeUpdate();
            }
            connection.commit();
        }
    }

    private static UUID queryParentId(Connection connection, UUID requestKey) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM commit_probe_parent WHERE request_key = ?")) {
            select.setObject(1, requestKey);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Parent row missing during idempotent retry");
                }
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static Connection proxyConnection(String applicationName) throws SQLException {
        String url = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(POSTGRES_PROXY_PORT)
                + "/" + POSTGRES.getDatabaseName() + "?connectTimeout=3&socketTimeout=2&ApplicationName="
                + applicationName;
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection directConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int parentCount(UUID requestKey) throws SQLException {
        try (Connection connection = directConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM commit_probe_parent WHERE request_key = ?")) {
            query.setObject(1, requestKey);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int childCount(UUID requestKey) throws SQLException {
        try (Connection connection = directConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM commit_probe_child c JOIN commit_probe_parent p ON p.id = c.parent_id "
                        + "WHERE p.request_key = ?")) {
            query.setObject(1, requestKey);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static boolean backendExists(String applicationName) throws SQLException {
        try (Connection connection = directConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ?)")) {
            query.setString(1, applicationName);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void awaitCondition(SqlCondition condition, String failureMessage) throws Exception {
        long deadline = System.nanoTime() + OBSERVER_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.isTrue()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Interrupted while waiting for PostgreSQL outcome");
            }
        }
        assertThat(condition.isTrue()).as(failureMessage).isTrue();
    }

    private static void awaitToxiproxyApiReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (sendApi("GET", "/version", null) == 200) {
                    return;
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Interrupted waiting for Toxiproxy API readiness");
            }
        }
        throw new IllegalStateException("Toxiproxy API /version did not become ready within 15 seconds", lastFailure);
    }

    private static void addToxic(String name, String type, String stream, String attributes) throws Exception {
        api("POST", "/proxies/postgres/toxics", "{\"name\":\"" + name + "\",\"type\":\"" + type
                + "\",\"stream\":\"" + stream + "\",\"toxicity\":1.0,\"attributes\":{" + attributes + "}}");
    }

    private static void deleteToxic(String name) throws Exception {
        int status = sendApi("DELETE", "/proxies/postgres/toxics/" + name, null);
        assertThat(status == 404 || (status >= 200 && status < 300))
                .as("Toxiproxy toxic deletion status for %s", name)
                .isTrue();
    }

    private static void api(String method, String path, String body) throws Exception {
        assertThat(sendApi(method, path, body)).as("Toxiproxy API %s %s", method, path)
                .isBetween(200, 299);
    }

    private static int sendApi(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + TOXIPROXY.getHost() + ":"
                        + TOXIPROXY.getMappedPort(TOXIPROXY_API_PORT) + path))
                .timeout(Duration.ofSeconds(3));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<Void> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    @FunctionalInterface
    private interface SqlCondition {
        boolean isTrue() throws Exception;
    }
}
