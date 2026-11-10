package com.atlas.application.controller.admission;

import com.atlas.account.domain.AccountException;
import com.atlas.account.domain.AccountException.Code;
import com.atlas.account.service.AccountService;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.application.controller.dto.JoinQueueHttpRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/campaigns/{campaignId}/tickets")
public class AdmissionController {
    private final AdmissionService admission;
    private final AccountService accounts;

    public AdmissionController(AdmissionService admission, AccountService accounts) {
        this.admission = admission;
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QueueTicketResponse join(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID campaignId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody JoinQueueHttpRequest request) {
        UUID accountId = UUID.fromString(jwt.getSubject());
        String buyerRef = accounts.buyerRef(accountId);
        if (!buyerRef.equals(request.buyerRef())) {
            throw new AccountException(Code.RESOURCE_NOT_OWNED, "Buyer identity is not available to this account");
        }
        return admission.join(new JoinQueueRequest(campaignId, request.skuId(), buyerRef, idempotencyKey));
    }

    @GetMapping("/{ticketId}")
    public QueueTicketResponse find(@PathVariable UUID campaignId, @PathVariable UUID ticketId) {
        return admission.find(campaignId, ticketId);
    }
}
