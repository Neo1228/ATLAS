package com.atlas.application.controller.account;

import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.service.AccountService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;

/**
 * 운영자 계정 정지 API 입니다.
 *
 * <p>{@code /api/internal/**} 경로는 일반 사용자 토큰으로 접근할 수 없습니다.
 * 별도 보안 체인이 운영자 자격증명을 요구합니다.
 *
 * <p>idempotency-key 를 헤더로 받는 이유: 운영 자동화가 재시도할 때 감사 기록이
 * 중복되지 않아야 합니다. 본문에 넣으면 사유와 섞여 실수로 같은 키를 재사용하기 쉽습니다.
 */
@ConditionalOnProperty(name = "atlas.account.suspension.operator.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/internal/accounts")
public class AccountSuspensionOperatorController {
    private final AccountService accounts;

    public AccountSuspensionOperatorController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/{accountId}/suspension")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(Principal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID accountId,
            @Valid @RequestBody AccountRequest.SuspendAccount request) {
        accounts.suspendAccount(accountId, request.reason(), actor(principal), idempotencyKey);
    }

    @DeleteMapping("/{accountId}/suspension")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(Principal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID accountId,
            @Valid @RequestBody AccountRequest.SuspendAccount request) {
        accounts.releaseAccount(accountId, request.reason(), actor(principal), idempotencyKey);
    }

    @GetMapping("/{accountId}/suspension/history")
    public List<AccountResponse.SuspensionAction> history(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "20") int limit) {
        return accounts.suspensionHistory(accountId, limit);
    }

    private static String actor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "operator identity is required");
        }
        return principal.getName();
    }
}
