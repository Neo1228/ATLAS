package com.atlas.application.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.account.exception.AccountException;
import com.atlas.account.service.AccountService;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.CampaignService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerMapping;

class PaymentRecoveryStatusOwnershipTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void paymentStatusRouteRequiresOrderOwnership() {
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String buyerRef = "buyer-123";
        AccountService accounts = mock(AccountService.class);
        OrderService orders = mock(OrderService.class);
        AccountOwnershipInterceptor interceptor = new AccountOwnershipInterceptor(accounts, orders,
                mock(CheckoutPaymentService.class), mock(CampaignService.class), mock(AdmissionService.class));
        Jwt token = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(accountId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        when(accounts.buyerRef(accountId)).thenReturn(buyerRef);
        when(orders.isOwnedByBuyer(orderId, buyerRef)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/orders/" + orderId + "/payment/status");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/orders/{orderId}/payment/status");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("orderId", orderId.toString()));

        assertThrows(AccountException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(orders).isOwnedByBuyer(orderId, buyerRef);
    }
}
