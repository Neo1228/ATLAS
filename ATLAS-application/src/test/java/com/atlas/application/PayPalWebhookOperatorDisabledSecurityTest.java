package com.atlas.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.config.AccountSecurityConfiguration;
import com.atlas.application.config.ApiSecurityConfiguration;
import com.atlas.application.controller.PayPalWebhookOperatorController;
import com.atlas.application.security.AccountOwnershipInterceptor;
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
@Import({AccountSecurityConfiguration.class, ApiSecurityConfiguration.class})
@EnableWebSecurity
@TestPropertySource(properties = "atlas.payment.webhook-retry.operator.enabled=false")
class PayPalWebhookOperatorDisabledSecurityTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    AccountOwnershipInterceptor ownership;

    @BeforeEach
    void allowRequestsPastAccountOwnershipInterceptor() throws Exception {
        when(ownership.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void validJwtCannotReachDisabledOperatorEndpoint() throws Exception {
        mvc.perform(get("/api/internal/payment/paypal/webhook-retries/exhausted").with(jwt()))
                .andExpect(status().isNotFound());
    }
}
