package com.atlas.application.controller.order;

import com.atlas.account.service.AccountService;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.response.RefundResponse;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 정지·폐쇄된 Seller 계정의 결제 완료 주문을 운영자가 찾고 환불하는 API다(ADR-0112).
 *
 * <p>정지된 Seller는 로그인할 수 없어 Seller 환불 경로를 쓸 수 없다. 계정 정지와 같은 운영자 보안 체인
 * ({@code /api/internal/accounts/**}, {@code AccountSuspensionOperatorSecurityConfiguration})과 같은 프로퍼티로 켠다.
 */
@ConditionalOnProperty(name = "atlas.account.suspension.operator.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/internal/accounts/{accountId}/sellers/{sellerId}/orders")
public class SellerSuspensionOrderOperatorController {
    private final AccountService accounts;
    private final OrderService orders;
    private final OrderRefundService refunds;

    public SellerSuspensionOrderOperatorController(AccountService accounts, OrderService orders,
            OrderRefundService refunds) {
        this.accounts = accounts;
        this.orders = orders;
        this.refunds = refunds;
    }

    @GetMapping
    public OrderViewResponse.HistoryPage orders(@PathVariable UUID accountId, @PathVariable UUID sellerId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        requireLinkedSeller(accountId, sellerId);
        return orders.findSellerOrderHistory(sellerId, limit, cursor);
    }

    @PostMapping("/{orderId}/refund")
    public RefundResponse.Refund refund(Principal principal, @PathVariable UUID accountId,
            @PathVariable UUID sellerId, @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        String actor = actor(principal);
        requireLinkedSeller(accountId, sellerId);
        return refunds.refundForDisabledSeller(new OrderRequest.OperatorRefund(sellerId, orderId, idempotencyKey, actor));
    }

    private void requireLinkedSeller(UUID accountId, UUID sellerId) {
        if (!accounts.ownsSeller(accountId, sellerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller is not linked to the account");
        }
    }

    private static String actor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "operator identity is required");
        }
        return principal.getName();
    }
}
