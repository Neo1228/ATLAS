package com.atlas.application.controller.settlement;

import com.atlas.application.dto.request.PayPalBalanceReportDispositionRequest;
import com.atlas.settlement.dto.response.PayPalBalanceReportDispositionResponse;
import com.atlas.settlement.dto.response.PayPalBalanceReportOperatorSummary;
import com.atlas.settlement.dto.response.PayPalBalanceReportPage;
import com.atlas.settlement.dto.response.PayPalBalanceReportRowResponse;
import com.atlas.settlement.dto.response.PayPalBalanceReportRunResponse;
import com.atlas.settlement.service.PayPalBalanceReportOperatorService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/settlement/paypal/balance-report/operator")
@ConditionalOnProperty(name = "atlas.settlement.report.operator.enabled", havingValue = "true")
public class PayPalBalanceReportOperatorController {
    private final PayPalBalanceReportOperatorService operator;

    public PayPalBalanceReportOperatorController(PayPalBalanceReportOperatorService operator) {
        this.operator = operator;
    }

    @GetMapping("/summary")
    public PayPalBalanceReportOperatorSummary summary() {
        return operator.summary();
    }

    @GetMapping("/runs")
    public PayPalBalanceReportPage<PayPalBalanceReportRunResponse> runs(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return operator.listRuns(limit, offset);
    }

    @GetMapping("/runs/search")
    public PayPalBalanceReportRunResponse search(@RequestParam String reportSha256) {
        return operator.search(reportSha256);
    }

    @GetMapping("/exceptions")
    public PayPalBalanceReportPage<PayPalBalanceReportRowResponse> exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String recordId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return operator.listExceptions(status, from, to, recordId, limit, offset);
    }

    @GetMapping("/runs/{runId}/rows")
    public PayPalBalanceReportPage<PayPalBalanceReportRowResponse> rows(@PathVariable UUID runId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String recordId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return operator.listRunRows(runId, status, recordId, limit, offset);
    }

    @GetMapping({"/runs/{runId}/rows/{rowNumber}/history", "/runs/{runId}/rows/{rowNumber}/dispositions"})
    public List<PayPalBalanceReportDispositionResponse> dispositionHistory(@PathVariable UUID runId,
            @PathVariable int rowNumber) {
        return operator.dispositionHistory(runId, rowNumber);
    }

    @PostMapping("/runs/{runId}/rows/{rowNumber}/dispositions")
    @ResponseStatus(HttpStatus.CREATED)
    public PayPalBalanceReportDispositionResponse recordDisposition(@PathVariable UUID runId,
            @PathVariable int rowNumber,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayPalBalanceReportDispositionRequest request,
            Principal principal) {
        return operator.recordDisposition(runId, rowNumber, request.disposition(), principal.getName(),
                request.reason(), idempotencyKey);
    }
}
