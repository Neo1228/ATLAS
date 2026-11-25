package com.atlas.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.config.AccountSecurityConfiguration;
import com.atlas.application.config.SettlementPayoutOperatorSecurityConfiguration;
import com.atlas.application.controller.settlement.PayPalPayoutOperatorController;
import com.atlas.application.controller.settlement.SettlementPayoutProfileOperatorController;
import com.atlas.application.security.AccountOwnershipInterceptor;
import com.atlas.application.service.payout.PayoutDispatchApprovalService;
import com.atlas.application.service.payout.PayoutManualOperatorService;
import com.atlas.application.service.payout.SellerPayoutProfileService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Seller 지급 승인과 지급 프로필 검증 경로의 인증 경계.
 *
 * <p>ADR-0078 기준으로 이 경로는 설정된 단일 운영자 자격 증명이 유일한 관문이다. 이 자격 증명 없이는
 * 어떤 서비스도 호출되지 않아야 하고, 감사 행에 남는 actor 는 요청 본문이 아니라 인증된 principal 이어야 한다.
 */
@WebMvcTest(controllers = {PayPalPayoutOperatorController.class, SettlementPayoutProfileOperatorController.class})
@Import({SettlementPayoutOperatorSecurityConfiguration.class, AccountSecurityConfiguration.class})
@EnableWebSecurity
@TestPropertySource(properties = {
        "atlas.settlement.payout.operator.enabled=true",
        "atlas.settlement.payout.operator.username=payout-operator",
        "atlas.settlement.payout.operator.password=0123456789abcdef0123456789abcdef",
        "atlas.payment.paypal.payouts.enabled=true"
})
class PayoutOperatorSecurityTest {
    private static final UUID PAYOUT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String OPERATOR = "payout-operator";
    private static final String OPERATOR_PASSWORD = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PayoutDispatchApprovalService approvals;

    @MockitoBean
    private PayoutManualOperatorService manualPayouts;

    @MockitoBean
    private SellerPayoutProfileService profiles;

    @MockitoBean
    private AccountOwnershipInterceptor ownership;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void allowRequestsPastAccountOwnershipInterceptor() throws Exception {
        when(ownership.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void anonymousAndInvalidCredentialsCannotApprovePayoutOrVerifyProfile() throws Exception {
        mockMvc.perform(approve()).andExpect(status().isUnauthorized());
        mockMvc.perform(approve().with(httpBasic(OPERATOR, "wrong-password")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(verifyProfile()).andExpect(status().isUnauthorized());
        mockMvc.perform(verifyProfile().with(httpBasic(OPERATOR, "wrong-password")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(approvals, manualPayouts, profiles);
    }

    @Test
    void auditActorComesFromAuthenticatedPrincipalNotFromRequestBody() throws Exception {
        mockMvc.perform(approve().with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isAccepted());
        mockMvc.perform(verifyProfile().with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk());

        verify(approvals).approve(eq(PAYOUT_ID), eq("approve-key"), eq(OPERATOR), eq("reviewed"));
        verify(profiles).verify(eq(SELLER_ID), eq(OPERATOR), eq("verify-key"), any());
        verify(manualPayouts, never()).approve(any(), any(), any(), any());
    }

    private static MockHttpServletRequestBuilder approve() {
        return post("/api/internal/settlement/payouts/{payoutId}/approve", PAYOUT_ID)
                .header("Idempotency-Key", "approve-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"reviewed\"}");
    }

    private static MockHttpServletRequestBuilder verifyProfile() {
        return post("/api/internal/settlement/payout-profiles/{sellerId}/verify", SELLER_ID)
                .header("Idempotency-Key", "verify-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileVersion\":1,\"evidenceReference\":\"case-1\"}");
    }
}
