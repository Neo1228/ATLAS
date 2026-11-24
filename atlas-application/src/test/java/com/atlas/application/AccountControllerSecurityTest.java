package com.atlas.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.service.AccountService;
import com.atlas.application.config.ApiSecurityConfiguration;
import com.atlas.application.controller.account.AccountController;
import com.atlas.application.security.AccountOwnershipInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AccountController.class)
@Import(ApiSecurityConfiguration.class)
@EnableWebSecurity
class AccountControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accounts;

    @MockitoBean
    private AccountOwnershipInterceptor ownership;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void allowRequestsPastAccountOwnershipInterceptor() throws Exception {
        when(ownership.preHandle(any(), any(), any())).thenReturn(true);
        when(accounts.registerBuyer(any(), any())).thenReturn(new AccountResponse.Action(true));
    }

    @Test
    void anonymousSignupReceivesTheGenericAcceptedResponse() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"buyer@example.com","password":"long-password-123"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void mfaManagementEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/mfa/enroll"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/mfa/enable"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/mfa/disable"))
                .andExpect(status().isUnauthorized());
    }
}
