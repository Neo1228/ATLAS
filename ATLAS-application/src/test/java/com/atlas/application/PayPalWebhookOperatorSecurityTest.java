package com.atlas.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.config.AccountSecurityConfiguration;
import com.atlas.application.config.PayPalReportOperatorSecurityConfiguration;
import com.atlas.application.config.PayPalWebhookOperatorSecurityConfiguration;
import com.atlas.application.controller.PayPalWebhookOperatorController;
import com.atlas.application.dto.response.PayPalWebhookExhaustedRetryPage;
import com.atlas.application.dto.response.PayPalWebhookExhaustedRetryPage.Item;
import com.atlas.application.dto.response.PayPalWebhookRequeueResponse;
import com.atlas.application.security.AccountOwnershipInterceptor;
import com.atlas.application.service.PayPalWebhookOperatorService;
import java.time.Instant;
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

@WebMvcTest(controllers = PayPalWebhookOperatorController.class)
@Import({PayPalWebhookOperatorSecurityConfiguration.class, PayPalReportOperatorSecurityConfiguration.class,
        AccountSecurityConfiguration.class})
@EnableWebSecurity
@TestPropertySource(properties = {
        "atlas.payment.webhook-retry.operator.enabled=true",
        "atlas.payment.webhook-retry.operator.username=webhook-operator",
        "atlas.payment.webhook-retry.operator.password=0123456789abcdef0123456789abcdef",
        "atlas.settlement.report.operator.enabled=true",
        "atlas.settlement.report.operator.username=settlement-operator",
        "atlas.settlement.report.operator.password=abcdef0123456789abcdef0123456789"
})
class PayPalWebhookOperatorSecurityTest {
    private static final String PATH = "/api/internal/payment/paypal/webhook-retries";
    private static final String PASSWORD = "0123456789abcdef0123456789abcdef";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PayPalWebhookOperatorService service;

    @MockitoBean
    AccountOwnershipInterceptor ownership;

    @BeforeEach
    void allowRequestsPastAccountOwnershipInterceptor() throws Exception {
        when(ownership.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void unauthenticatedAndWrongOperatorCannotReadOrRequeue() throws Exception {
        mvc.perform(get(PATH + "/exhausted")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/exhausted").with(httpBasic("webhook-operator", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/event-1/requeue").with(httpBasic("webhook-operator", "wrong-password"))
                        .header("Idempotency-Key", "key-1").contentType("application/json")
                        .content("{\"reason\":\"manual review\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedReadReturnsSafePageAndBoundsRequestedSize() throws Exception {
        when(service.listExhausted(0, 100)).thenReturn(new PayPalWebhookExhaustedRetryPage(
                List.of(new Item("event-1", "PAYMENT.CAPTURE.COMPLETED", 8,
                        Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-01T10:01:00Z"))),
                0, 100, 1, 1));

        mvc.perform(get(PATH + "/exhausted?page=0&size=1000").with(httpBasic("webhook-operator", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value("event-1"))
                .andExpect(jsonPath("$.content[0].eventType").value("PAYMENT.CAPTURE.COMPLETED"))
                .andExpect(jsonPath("$.content[0].attemptCount").value(8))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].resourceAmount").doesNotExist())
                .andExpect(jsonPath("$.content[0].lastError").doesNotExist());
        verify(service).listExhausted(0, 100);
        mvc.perform(get(PATH + "/exhausted?page=101&size=100").with(httpBasic("webhook-operator", PASSWORD)))
                .andExpect(status().isBadRequest());
        verify(service, never()).listExhausted(101, 100);
    }

    @Test
    void authenticatedRequeueReturnsAcceptedAndUsesOperatorPrincipal() throws Exception {
        when(service.requeue("event-1", "key-1", "webhook-operator", "manual review"))
                .thenReturn(new PayPalWebhookRequeueResponse("event-1", "REQUEUED"));

        mvc.perform(post(PATH + "/event-1/requeue").with(httpBasic("webhook-operator", PASSWORD))
                        .header("Idempotency-Key", "key-1").contentType("application/json")
                        .content("{\"reason\":\"manual review\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("event-1"))
                .andExpect(jsonPath("$.outcome").value("REQUEUED"));
        verify(service).requeue("event-1", "key-1", "webhook-operator", "manual review");
        verify(service, never()).listExhausted(0, 0);
    }

    @Test
    void operatorCredentialsAreIsolatedBetweenOperatorChains() throws Exception {
        mvc.perform(get("/api/internal/settlement/paypal/balance-report/reconciliation")
                        .with(httpBasic("webhook-operator", PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/exhausted")
                        .with(httpBasic("settlement-operator", "abcdef0123456789abcdef0123456789")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
