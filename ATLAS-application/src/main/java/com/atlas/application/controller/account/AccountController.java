package com.atlas.application.controller.account;

import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.RefreshTokenRequest;
import com.atlas.account.dto.request.RegisterBuyerRequest;
import com.atlas.account.dto.response.AccountTokenResponse;
import com.atlas.account.service.AccountService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
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
    @ResponseStatus(HttpStatus.CREATED)
    public AccountTokenResponse registerBuyer(@Valid @RequestBody RegisterBuyerRequest request) {
        return accounts.registerBuyer(request);
    }

    @PostMapping("/login")
    public AccountTokenResponse login(@Valid @RequestBody LoginRequest request) {
        return accounts.login(request);
    }

    @PostMapping("/refresh")
    public AccountTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return accounts.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefreshTokenRequest request) {
        accounts.logout(UUID.fromString(jwt.getSubject()), request.refreshToken());
    }
}
