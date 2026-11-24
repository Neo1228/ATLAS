package com.atlas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.application.dto.response.SettlementHttpResponse;
import com.atlas.settlement.dto.response.PayPalBalanceReportResponse;
import com.atlas.payment.dto.response.PayPalReportMatchResponse;
import com.atlas.application.service.PayPalBalanceReportReconciliationService;
import com.atlas.application.service.PayPalBalanceReportRunIngestionService;
import com.atlas.payment.service.PayPalReportMatchQuery;
import com.atlas.schema.BaselineSchema;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.repository.JdbcPayPalBalanceReportStore;
import com.atlas.settlement.service.PayPalBalanceReportOperatorService;
import com.atlas.settlement.service.PayPalBalanceReportParser;
import com.atlas.settlement.service.impl.CsvPayPalBalanceReportParser;
import com.atlas.settlement.service.impl.PayPalBalanceReportOperatorServiceImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class PayPalBalanceReportReconciliationWorkflowIntegrationTest {

    private static final String REPORT = """
            RH,Report name,Report status,Report generated,Hierarchy,Timezone
            RH,BALANCE_TRANSACTIONAL_REPORT,Success,15/02/22,synthetic,UTC
            RS,Account ID,Balance,Currency,Available,Withheld,Total
            RD,Record ID,Related record ID,Original record ID,Record type,Record subtype,Record currency,Gross  amount,Total fees net billed,Net  amount,Transfer ID
            RD,capture-1,,capture-1,Sale,,USD,100.00,-3.00,97.00,
            RD,refund-1,capture-1,capture-1,Refund,Referenced,USD,-10.00,0,-10.00,
            RD,capture-mismatch,,capture-mismatch,Sale,,USD,49.99,-1.00,48.99,
            RD,capture-duplicate,,capture-duplicate,Sale,,USD,5.00,0,5.00,
            RD,capture-duplicate,,capture-duplicate,Sale,,USD,5.00,0,5.00,
            RD,missing-capture,,missing-capture,Sale,,USD,2.00,0,2.00,
            RD,withdrawal-1,,,Withdraw money,,USD,-90.00,0,-90.00,bank-transfer-1
            RF,File number,,Total records,Total files,,,,,Total records
            RF,1,,7,1,,,,,7
            """;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;
    private PayPalBalanceReportRunIngestionService ingestion;
    private PayPalBalanceReportOperatorService operator;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute(BaselineSchema.script());

        PayPalReportMatchQuery matchQuery = mock(PayPalReportMatchQuery.class);
        when(matchQuery.findByRecordIds(any())).thenReturn(List.of(
                new PayPalReportMatchResponse.Candidate("capture-1", PayPalReportMatchResponse.Kind.CAPTURE,
                        true, "capture-1", 10_000L, "USD"),
                new PayPalReportMatchResponse.Candidate("refund-1", PayPalReportMatchResponse.Kind.REFUND,
                        true, "capture-1", 1_000L, "USD"),
                new PayPalReportMatchResponse.Candidate("capture-mismatch", PayPalReportMatchResponse.Kind.CAPTURE,
                        true, "capture-mismatch", 5_000L, "USD")));
        JdbcPayPalBalanceReportStore store = new JdbcPayPalBalanceReportStore(jdbc);
        PayPalBalanceReportReconciliationService reconciliation =
                new PayPalBalanceReportReconciliationService(matchQuery, new CsvPayPalBalanceReportParser());
        ingestion = new PayPalBalanceReportRunIngestionService(reconciliation, store);
        operator = new PayPalBalanceReportOperatorServiceImpl(store);
    }

    @Test
    void repeatedReportReturnsPersistedRunAndDoesNotCreateMoreRows() {
        var first = ingestion.reconcile(REPORT.getBytes(StandardCharsets.UTF_8));
        var replay = ingestion.reconcile(REPORT.getBytes(StandardCharsets.UTF_8));

        assertThat(first.runId()).isNotNull();
        assertThat(first.duplicateUpload()).isFalse();
        assertThat(replay.runId()).isEqualTo(first.runId());
        assertThat(replay.uploadedAt()).isEqualTo(first.uploadedAt());
        assertThat(replay.duplicateUpload()).isTrue();
        assertThat(replay.rows()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(first.rows());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_row", Integer.class))
                .isEqualTo(first.summary().totalRows());
    }

    @Test
    void exceptionSearchAndDispositionAreBoundedAuditedAndIdempotent() {
        var run = ingestion.reconcile(REPORT.getBytes(StandardCharsets.UTF_8));
        var exception = run.rows().stream()
                .filter(row -> row.status() == SettlementHttpResponse.PayPalBalanceReportReconciliation.Status.UNMATCHED)
                .findFirst().orElseThrow();

        var matched = run.rows().stream()
                .filter(row -> row.status() == SettlementHttpResponse.PayPalBalanceReportReconciliation.Status.MATCHED)
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> operator.recordDisposition(run.runId(), matched.rowNumber(), "DEFERRED",
                "settlement-operator", "not an exception", "matched-row-key"))
                .isInstanceOfSatisfying(SettlementException.class,
                        error -> assertThat(error.code()).isEqualTo(SettlementException.Code.CONFLICT));

        var first = operator.recordDisposition(run.runId(), exception.rowNumber(), "DEFERRED",
                "settlement-operator", "Awaiting external evidence", "disposition-key-1");
        var replay = operator.recordDisposition(run.runId(), exception.rowNumber(), "DEFERRED",
                "settlement-operator", "Awaiting external evidence", "disposition-key-1");
        assertThat(replay.dispositionId()).isEqualTo(first.dispositionId());
        assertThatThrownBy(() -> operator.recordDisposition(run.runId(), exception.rowNumber(), "DEFERRED",
                "settlement-operator", "different reason", "disposition-key-1"))
                .isInstanceOfSatisfying(SettlementException.class,
                        error -> assertThat(error.code()).isEqualTo(SettlementException.Code.CONFLICT));

        var history = operator.dispositionHistory(run.runId(), exception.rowNumber());
        assertThat(history).hasSize(1);
        assertThat(Arrays.stream(PayPalBalanceReportResponse.Disposition.class.getRecordComponents())
                .map(component -> component.getName()).toList()).doesNotContain("idempotencyKey");
        assertThat(operator.summary().pendingDispositions()).isEqualTo(4);

        var page = operator.listExceptions("UNMATCHED", null, null, exception.recordId(), 20, 0);
        assertThat(page.items()).extracting(PayPalBalanceReportResponse.Row::recordId).contains(exception.recordId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_disposition", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateUploadsAndDispositionsPersistOnlyOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            byte[] content = REPORT.getBytes(StandardCharsets.UTF_8);
            List<Callable<SettlementHttpResponse.PayPalBalanceReportReconciliation>> uploadTasks = List.of(
                    () -> ingestion.reconcile(content),
                    () -> ingestion.reconcile(content));
            var uploadResults = executor.invokeAll(uploadTasks);
            var firstRun = uploadResults.get(0).get();
            var replay = uploadResults.get(1).get();

            assertThat(firstRun.runId()).isEqualTo(replay.runId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_run", Integer.class))
                    .isEqualTo(1);

            var exception = firstRun.rows().stream()
                    .filter(row -> row.status() == SettlementHttpResponse.PayPalBalanceReportReconciliation.Status.UNMATCHED)
                    .findFirst().orElseThrow();
            List<Callable<PayPalBalanceReportResponse.Disposition>> dispositionTasks = List.of(
                    () -> operator.recordDisposition(firstRun.runId(), exception.rowNumber(), "DEFERRED",
                            "settlement-operator", "Awaiting external evidence", "concurrent-key"),
                    () -> operator.recordDisposition(firstRun.runId(), exception.rowNumber(), "DEFERRED",
                            "settlement-operator", "Awaiting external evidence", "concurrent-key"));
            var dispositionResults = executor.invokeAll(dispositionTasks);

            assertThat(dispositionResults.get(0).get().dispositionId())
                    .isEqualTo(dispositionResults.get(1).get().dispositionId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_disposition", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsUnsupportedStatusAndClampsOperatorPages() {
        assertThat(operator.listRuns(101, 0).limit()).isEqualTo(100);
        assertThatThrownBy(() -> operator.listExceptions("MATCHED", null, null, null, 20, 0))
                .isInstanceOfSatisfying(SettlementException.class,
                        error -> assertThat(error.code()).isEqualTo(SettlementException.Code.INVALID_REQUEST));
        assertThat(operator.listExceptions("UNMATCHED", null, null, null, 101, 0).limit()).isEqualTo(100);
        assertThatThrownBy(() -> operator.listExceptions("UNMATCHED", null, null, null, 20, 10_001))
                .isInstanceOfSatisfying(SettlementException.class,
                        error -> assertThat(error.code()).isEqualTo(SettlementException.Code.INVALID_REQUEST));
    }
}
