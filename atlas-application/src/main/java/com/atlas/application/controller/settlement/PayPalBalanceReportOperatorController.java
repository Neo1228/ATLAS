package com.atlas.application.controller.settlement;

import com.atlas.application.dto.request.SettlementHttpRequest;
import com.atlas.settlement.dto.response.PayPalBalanceReportResponse;
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
    public PayPalBalanceReportResponse.OperatorSummary summary() {
        return operator.summary();
    }

    @GetMapping("/runs")
    public PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Run> runs(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return operator.listRuns(limit, offset);
    }

    @GetMapping("/runs/search")
    public PayPalBalanceReportResponse.Run search(@RequestParam String reportSha256) {
        return operator.search(reportSha256);
    }

    @GetMapping("/exceptions")
    public PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Row> exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String recordId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return operator.listExceptions(status, from, to, recordId, limit, offset);
    }

    @GetMapping("/runs/{runId}/rows")
    public PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Row> rows(@PathVariable UUID runId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String recordId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return operator.listRunRows(runId, status, recordId, limit, offset);
    }

    @GetMapping({"/runs/{runId}/rows/{rowNumber}/history", "/runs/{runId}/rows/{rowNumber}/dispositions"})
    public List<PayPalBalanceReportResponse.Disposition> dispositionHistory(@PathVariable UUID runId,
            @PathVariable int rowNumber) {
        return operator.dispositionHistory(runId, rowNumber);
    }

    @PostMapping("/runs/{runId}/rows/{rowNumber}/dispositions")
    @ResponseStatus(HttpStatus.CREATED)
    public PayPalBalanceReportResponse.Disposition recordDisposition(@PathVariable UUID runId,
            @PathVariable int rowNumber,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayPalBalanceReportDisposition request,
            Principal principal) {
        return operator.recordDisposition(runId, rowNumber, request.disposition(), principal.getName(),
                request.reason(), idempotencyKey);
    }
}
