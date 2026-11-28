package com.atlas.application.controller.payment;

import com.atlas.application.dto.request.PaymentHttpRequest;
import com.atlas.application.service.PaymentRecoveryOperatorService;
import com.atlas.order.dto.response.RefundResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재전송 기한이 지나 결과 불명으로 멈춘 환불을 운영자가 닫는 경로(ADR-0108).
 *
 * <p>판매자 원장을 움직이는 운영자 행위라 지급 운영자와 같은 인증 경계(사람별 account, MFA 세션,
 * {@code payout_operator_account} 권한)를 쓴다. 그 경계를 만드는 {@code SettlementPayoutOperatorSecurityConfiguration}과
 * 같은 프로퍼티로 켜서, 경계 없이 이 경로만 열리는 구성이 없게 한다.
 */
@RestController
@RequestMapping("/api/internal/payment/refunds")
@ConditionalOnProperty(name = "atlas.settlement.payout.operator.enabled", havingValue = "true")
public class PaymentRefundOperatorController {
    private final PaymentRecoveryOperatorService service;

    public PaymentRefundOperatorController(PaymentRecoveryOperatorService service) {
        this.service = service;
    }

    @PostMapping("/{refundOperationId}/resolve-expired")
    public RefundResponse.Refund resolveExpired(@PathVariable UUID refundOperationId,
            @Valid @RequestBody PaymentHttpRequest.ExpiredRefundResolution request, Principal principal) {
        return service.resolveExpiredRefund(refundOperationId, principal == null ? null : principal.getName(), request);
    }
}
