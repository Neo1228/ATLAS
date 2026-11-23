package com.atlas.application;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.config.AccountSecurityConfiguration;
import com.atlas.application.config.PayPalReportOperatorSecurityConfiguration;
import com.atlas.application.controller.settlement.PayPalBalanceReportOperatorController;
import com.atlas.application.dto.response.PayPalBalanceReportOperatorSummary;
import com.atlas.application.service.PayPalBalanceReportOperatorService;
import com.atlas.application.security.AccountOwnershipInterceptor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PayPalBalanceReportOperatorController.class)
@Import({PayPalReportOperatorSecurityConfiguration.class, AccountSecurityConfiguration.class})
@EnableWebSecurity
@TestPropertySource(properties = {
        "atlas.settlement.report.operator.enabled=true",
        "atlas.settlement.report.operator.username=settlement-operator",
        "atlas.settlement.report.operator.password=0123456789abcdef0123456789abcdef"
})
class PayPalBalanceReportOperatorSecurityTest {

    private static final String PATH = "/api/internal/settlement/paypal/balance-report/operator/summary";
    private static final String OPERATOR_PASSWORD = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PayPalBalanceReportOperatorService operator;

    @MockitoBean
    private AccountOwnershipInterceptor ownership;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(operator.summary()).thenReturn(new PayPalBalanceReportOperatorSummary(List.of(), 0, 0, 0));
    }

    @Test
    void requiresOperatorCredentialsAndAllowsConfiguredOperator() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).with(httpBasic("settlement-operator", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).with(httpBasic("settlement-operator", OPERATOR_PASSWORD)))
                .andExpect(status().isOk());
    }
}
