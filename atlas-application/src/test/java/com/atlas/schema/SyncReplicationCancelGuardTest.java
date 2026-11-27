package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 동기 복제 대기 중인 쓰기를 앱이 스스로 취소하지 않는다는 전제를 고정한다(ADR-0106 C4, EXP-117 H11).
 *
 * <p>PgJDBC의 질의 시간 제한({@code setQueryTimeout}, Spring 트랜잭션 시간 제한과 {@code
 * spring.jdbc.template.query-timeout}이 쓰는 경로)이 autocommit 쓰기의 {@code SyncRep} 대기를 취소하면 드라이버는
 * 성공과 경고를 돌려주고 그 커밋은 failover에서 사라졌다(3/3). {@code statement_timeout}과 {@code socketTimeout}은
 * 성공으로 보고하지 않았다. 그래서 쓰기에 질의 시간 제한을 걸 수 있는 설정과 호출을 허용 목록 밖에서 막는다.
 */
class SyncReplicationCancelGuardTest {
    /** 입장 만료 후보 읽기(SELECT 전용, xid 없음)라 동기 복제를 기다리지 않는다. */
    private static final String ALLOWED_QUERY_TIMEOUT =
            "atlas-admission/src/main/java/com/atlas/admission/repository/QueueRepository.java";
    private static final Pattern CONFIGURED_TIMEOUT =
            Pattern.compile("(?m)^\\s*(query-timeout|default-timeout)\\s*:");
    private static final Pattern TRANSACTION_TIMEOUT = Pattern.compile("@Transactional\\([^)]*timeout");

    private static Path root() {
        Path module = Path.of("").toAbsolutePath();
        return module.getFileName().toString().startsWith("atlas-") ? module.getParent() : module;
    }

    private static List<Path> mainSources(String glob) throws IOException {
        var matcher = root().getFileSystem().getPathMatcher("glob:" + glob);
        try (Stream<Path> files = Files.walk(root())) {
            return files.filter(path -> path.toString().contains("/src/main/"))
                    .filter(path -> !path.toString().contains("/build/"))
                    .filter(path -> matcher.matches(path.getFileName()))
                    .toList();
        }
    }

    @Test
    void onlyTheReadOnlyAdmissionScanSetsAJdbcQueryTimeout() throws IOException {
        List<String> callers = mainSources("*.java").stream()
                .filter(path -> read(path).contains("setQueryTimeout("))
                .map(path -> root().relativize(path).toString())
                .toList();
        assertThat(callers).containsExactly(ALLOWED_QUERY_TIMEOUT);
    }

    @Test
    void noTransactionOrTemplateQueryTimeoutIsConfigured() throws IOException {
        assertThat(mainSources("*.java")).noneMatch(path -> TRANSACTION_TIMEOUT.matcher(read(path)).find());
        assertThat(mainSources("application*.yml")).noneMatch(path -> {
            // 주석이 아닌 설정 키만 본다.
            return CONFIGURED_TIMEOUT.matcher(read(path)).find();
        });
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
