package com.atlas.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.config.AccountSecurityConfiguration;
import com.atlas.application.config.PayPalReportOperatorSecurityConfiguration;
import com.atlas.application.controller.settlement.PayPalBalanceReportController;
import com.atlas.application.dto.response.PayPalBalanceReportReconciliationResponse;
import com.atlas.application.security.AccountOwnershipInterceptor;
import com.atlas.application.service.PayPalBalanceReportRunIngestionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PayPalBalanceReportController.class)
@Import({PayPalReportOperatorSecurityConfiguration.class, AccountSecurityConfiguration.class})
@EnableWebSecurity
@TestPropertySource(properties = {
        "atlas.settlement.report.operator.enabled=true",
        "atlas.settlement.report.operator.username=settlement-operator",
        "atlas.settlement.report.operator.password=0123456789abcdef0123456789abcdef"
})
class PayPalReportOperatorSecurityTest {
    private static final String PATH = "/api/internal/settlement/paypal/balance-report/reconciliation";
    private static final String OPERATOR_PASSWORD = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PayPalBalanceReportRunIngestionService reconciliation;

    @MockitoBean
    private AccountOwnershipInterceptor ownership;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void allowRequestsPastAccountOwnershipInterceptor() throws Exception {
        when(ownership.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void anonymousAndInvalidOperatorsCannotReadAccountReportFacts() throws Exception {
        mockMvc.perform(request()).andExpect(status().isUnauthorized());
        mockMvc.perform(request().with(httpBasic("settlement-operator", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredOperatorCanUploadReportAndReceiveReadOnlyResults() throws Exception {
        PayPalBalanceReportReconciliationResponse response = new PayPalBalanceReportReconciliationResponse(
                "BALANCE_RECONCILIATION_REPORT", "a".repeat(64), "NOT_VERIFIED_FROM_PAYPAL_REPORT_ALONE",
                "EXACT_PROVIDER_ID_WITH_AMOUNT_CURRENCY_VALIDATION",
                new PayPalBalanceReportReconciliationResponse.Summary(0, 0, 0, 0, 0), List.of());
        when(reconciliation.reconcile(any(byte[].class))).thenReturn(response);

        mockMvc.perform(request().with(httpBasic("settlement-operator", OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankReceiptEvidence").value("NOT_VERIFIED_FROM_PAYPAL_REPORT_ALONE"))
                .andExpect(jsonPath("$.providerIdMappingStatus")
                        .value("EXACT_PROVIDER_ID_WITH_AMOUNT_CURRENCY_VALIDATION"));
    }

    private static MockMultipartHttpServletRequestBuilder request() {
        return multipart(PATH).file(new MockMultipartFile("report", "balance.csv", "text/csv", new byte[] {1}));
    }
}
