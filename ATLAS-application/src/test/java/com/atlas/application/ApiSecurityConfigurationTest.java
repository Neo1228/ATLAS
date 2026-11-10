package com.atlas.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.application.security.AccountOwnershipInterceptor;
import securityprobe.SecurityProbeController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SecurityProbeController.class)
@Import(ApiSecurityConfiguration.class)
@EnableWebSecurity
class ApiSecurityConfigurationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountOwnershipInterceptor ownership;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void allowMvcProbeThroughOwnershipInterceptor() throws Exception {
        when(ownership.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void protectedRoutesRejectAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/private"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRoutesAcceptAuthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/private").with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void explicitlyPublicCatalogRouteAllowsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isNotFound());
    }

}
