package com.atlas.application.controller.settlement;

import com.atlas.application.dto.response.SettlementHttpResponse;
import com.atlas.application.service.PayPalBalanceReportRunIngestionService;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/internal/settlement/paypal/balance-report")
@ConditionalOnProperty(name = "atlas.settlement.report.operator.enabled", havingValue = "true")
public class PayPalBalanceReportController {
    private final PayPalBalanceReportRunIngestionService reconciliation;

    public PayPalBalanceReportController(PayPalBalanceReportRunIngestionService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping(path = "/reconciliation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SettlementHttpResponse.PayPalBalanceReportReconciliation reconcile(@RequestPart("report") MultipartFile report)
            throws IOException {
        return reconciliation.reconcile(report.getBytes());
    }
}