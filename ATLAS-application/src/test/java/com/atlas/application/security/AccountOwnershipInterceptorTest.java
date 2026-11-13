package com.atlas.application.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.account.domain.AccountException;
import com.atlas.account.service.AccountService;
import com.atlas.admission.service.AdmissionService;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.service.CampaignService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerMapping;

class AccountOwnershipInterceptorTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("fb9d9240-689a-4e83-82f9-617a54fd02f8");
    private final AccountService accounts = mock(AccountService.class);
    private final CampaignService campaigns = mock(CampaignService.class);
    private final AccountOwnershipInterceptor interceptor = new AccountOwnershipInterceptor(accounts,
            mock(OrderService.class), mock(CheckoutPaymentService.class), campaigns,
            mock(AdmissionService.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void authenticateAccount() {
        Jwt token = Jwt.withTokenValue("test-token").header("alg", "none").subject(ACCOUNT_ID.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void permitsSellerResourceOwnedByAuthenticatedAccount() {
        UUID sellerId = UUID.randomUUID();
        request.setRequestURI("/api/sellers/" + sellerId + "/products");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/sellers/{sellerId}/products");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("sellerId", sellerId.toString()));
        when(accounts.ownsSeller(ACCOUNT_ID, sellerId)).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void rejectsSellerResourceOwnedByAnotherAccount() {
        UUID sellerId = UUID.randomUUID();
        request.setRequestURI("/api/sellers/" + sellerId + "/products");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/sellers/{sellerId}/products");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("sellerId", sellerId.toString()));
        when(accounts.ownsSeller(ACCOUNT_ID, sellerId)).thenReturn(false);

        AccountException error = assertThrows(AccountException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        assertEquals(AccountException.Code.RESOURCE_NOT_OWNED, error.code());
    }

    @Test
    void rejectsSellerResourceOwnedByAnotherAccountWithServletContextPath() {
        UUID sellerId = UUID.randomUUID();
        request.setContextPath("/atlas");
        request.setRequestURI("/atlas/api/sellers/" + sellerId + "/products");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/sellers/{sellerId}/products");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("sellerId", sellerId.toString()));
        when(accounts.ownsSeller(ACCOUNT_ID, sellerId)).thenReturn(false);

        AccountException error = assertThrows(AccountException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        assertEquals(AccountException.Code.RESOURCE_NOT_OWNED, error.code());
    }

    @Test
    void rejectsCampaignManagementForCampaignOwnedByAnotherAccountWithServletContextPath() {
        UUID campaignId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        request.setMethod("POST");
        request.setContextPath("/atlas");
        request.setRequestURI("/atlas/api/campaigns/" + campaignId + "/open");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/campaigns/{campaignId}/open");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("campaignId", campaignId.toString()));
        when(campaigns.find(campaignId)).thenReturn(new CampaignResponse(campaignId, sellerId, null, null, null));
        when(accounts.ownsSeller(ACCOUNT_ID, sellerId)).thenReturn(false);

        AccountException error = assertThrows(AccountException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        assertEquals(AccountException.Code.RESOURCE_NOT_OWNED, error.code());
    }

    @Test
    void rejectsBuyerReferenceThatDoesNotBelongToAuthenticatedAccount() {
        String buyerRef = UUID.randomUUID().toString();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("buyerRef", buyerRef));
        when(accounts.ownsBuyer(ACCOUNT_ID, buyerRef)).thenReturn(false);

        AccountException error = assertThrows(AccountException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        assertEquals(AccountException.Code.RESOURCE_NOT_OWNED, error.code());
    }

    @Test
    void allowsBuyerToCartAnotherSellersSku() {
        String buyerRef = UUID.randomUUID().toString();
        UUID sellerId = UUID.randomUUID();
        request.setRequestURI("/api/buyers/" + buyerRef + "/cart/items/" + sellerId + "/sku-1");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("buyerRef", buyerRef, "sellerId", sellerId.toString(), "skuId", "sku-1"));
        when(accounts.ownsBuyer(ACCOUNT_ID, buyerRef)).thenReturn(true);
        when(accounts.ownsSeller(ACCOUNT_ID, sellerId)).thenReturn(false);

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }
}
