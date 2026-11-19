package com.atlas.application.controller.account;

import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.AccountActionTokenRequest;
import com.atlas.account.dto.request.PasswordResetConfirmRequest;
import com.atlas.account.dto.request.PasswordResetRequest;
import com.atlas.account.dto.request.MfaChallengeRequest;
import com.atlas.account.dto.request.CloseAccountRequest;
import com.atlas.account.dto.request.MfaCodeRequest;
import com.atlas.account.dto.request.RefreshTokenRequest;
import com.atlas.account.dto.request.RegisterBuyerRequest;
import com.atlas.account.dto.response.AccountTokenResponse;
import com.atlas.account.dto.response.AccountActionResponse;
import com.atlas.account.dto.response.AccountLoginResponse;
import com.atlas.account.dto.response.MfaEnrollmentResponse;
import com.atlas.account.dto.response.MfaRecoveryCodesResponse;
import com.atlas.account.dto.response.AccountSessionPage;
import com.atlas.account.service.AccountService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AccountActionResponse registerBuyer(@Valid @RequestBody RegisterBuyerRequest request) {
        return accounts.registerBuyer(request);
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody AccountActionTokenRequest request) {
        accounts.verifyEmail(request.token());
    }

    @PostMapping("/email-verification/request")
    public AccountActionResponse requestEmailVerification(@Valid @RequestBody PasswordResetRequest request) {
        return accounts.requestEmailVerification(request.email());
    }

    @PostMapping("/password-reset/request")
    public AccountActionResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        return accounts.requestPasswordReset(request.email());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        accounts.resetPassword(request.token(), request.newPassword());
    }

    @PostMapping("/login")
    public AccountLoginResponse login(@Valid @RequestBody LoginRequest request) {
        return accounts.login(request);
    }

    @PostMapping("/mfa/challenge")
    public AccountTokenResponse completeMfaLogin(@Valid @RequestBody MfaChallengeRequest request) {
        return accounts.completeMfaLogin(request);
    }

    @PostMapping("/mfa/enroll")
    public MfaEnrollmentResponse startMfaEnrollment(@AuthenticationPrincipal Jwt jwt) {
        return accounts.startMfaEnrollment(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/mfa/enable")
    public MfaRecoveryCodesResponse confirmMfaEnrollment(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MfaCodeRequest request) {
        return accounts.confirmMfaEnrollment(UUID.fromString(jwt.getSubject()), request.code());
    }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableMfa(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MfaCodeRequest request) {
        accounts.disableMfa(UUID.fromString(jwt.getSubject()), request.code());
    }

    @PostMapping("/refresh")
    public AccountTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return accounts.refresh(request);
    }

    @GetMapping("/sessions")
    public AccountSessionPage sessions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return accounts.listSessions(UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("sid")), limit, offset);
    }

    @DeleteMapping("/sessions/{familyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        accounts.revokeSession(UUID.fromString(jwt.getSubject()), familyId);
    }

    /**
     * 본인 계정을 폐쇄합니다.
     *
     * <p>비밀번호를 다시 요구하는 이유와 폐쇄 시 무효화되는 범위는
     * {@code AccountService#closeAccount} 에 기록되어 있습니다.
     *
     * <p>DELETE 를 쓰는 이유: 계정 자체를 더 이상 사용할 수 없게 만드는 의미이며,
     * 실제 행은 주문·결제 이력의 참조 무결성을 위해 남깁니다(상태만 DISABLED).
     */
    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CloseAccountRequest request) {
        accounts.closeAccount(UUID.fromString(jwt.getSubject()), request.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefreshTokenRequest request) {
        accounts.logout(UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("sid")), request.refreshToken());
    }
}