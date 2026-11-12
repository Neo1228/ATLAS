package com.atlas.application.security;

import com.atlas.account.domain.AccountException;
import com.atlas.account.domain.AccountException.Code;
import com.atlas.account.service.AccountService;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.service.CampaignService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AccountOwnershipInterceptor implements HandlerInterceptor {
    private final AccountService accounts;
    private final OrderService orders;
    private final CheckoutPaymentService checkouts;
    private final CampaignService campaigns;
    private final AdmissionService admission;

    public AccountOwnershipInterceptor(AccountService accounts, OrderService orders,
            CheckoutPaymentService checkouts, CampaignService campaigns, AdmissionService admission) {
        this.accounts = accounts;
        this.orders = orders;
        this.checkouts = checkouts;
        this.campaigns = campaigns;
        this.admission = admission;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return true;
        }
        UUID accountId = parseUuid(jwt.getToken().getSubject());
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables == null) return true;

        String buyerRef = variables.get("buyerRef");
        String sellerValue = variables.get("sellerId");
        UUID sellerId = sellerValue == null ? null : parseUuid(sellerValue);
        if (buyerRef != null && !accounts.ownsBuyer(accountId, buyerRef)) throw notOwned();
        // Buyer cart의 sellerId는 구매 대상이므로 Seller ownership으로 제한하지 않는다.
        if (sellerId != null && request.getRequestURI().startsWith("/api/sellers/")
                && !accounts.ownsSeller(accountId, sellerId)) throw notOwned();

        String checkoutValue = variables.get("checkoutId");
        if (checkoutValue != null) {
            CheckoutResponse checkout = checkouts.find(parseUuid(checkoutValue));
            if (!accounts.ownsBuyer(accountId, checkout.buyerRef())) throw notOwned();
        }

        String orderValue = variables.get("orderId");
        if (orderValue != null) {
            UUID orderId = parseUuid(orderValue);
            if (sellerId != null) {
                OrderResponse order = orders.find(orderId);
                if (order.items().isEmpty() || order.items().stream()
                        .anyMatch(item -> !sellerId.equals(item.sellerId()))) throw notOwned();
            } else {
                String ownerBuyerRef = buyerRef == null ? accounts.buyerRef(accountId) : buyerRef;
                if (!orders.isOwnedByBuyer(orderId, ownerBuyerRef)) throw notOwned();
            }
        }

        String ticketValue = variables.get("ticketId");
        String campaignValue = variables.get("campaignId");
        if (ticketValue != null && campaignValue != null) {
            QueueTicketResponse ticket = admission.find(parseUuid(campaignValue), parseUuid(ticketValue));
            if (!accounts.ownsBuyer(accountId, ticket.buyerRef())) throw notOwned();
        } else if (campaignValue != null && isCampaignManagement(request)) {
            CampaignResponse campaign = campaigns.find(parseUuid(campaignValue));
            if (!accounts.ownsSeller(accountId, campaign.sellerId())) throw notOwned();
        }
        return true;
    }

    private static boolean isPublicCampaignRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && request.getRequestURI().matches("/api/campaigns/[^/]+");
    }

    private static boolean isCampaignManagement(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getRequestURI().matches("/api/campaigns/[^/]+/open");
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new AccountException(Code.INVALID_REQUEST, "Resource identifier is invalid");
        }
    }

    private static AccountException notOwned() {
        return new AccountException(Code.RESOURCE_NOT_OWNED, "Resource is not available to this account");
    }
}
