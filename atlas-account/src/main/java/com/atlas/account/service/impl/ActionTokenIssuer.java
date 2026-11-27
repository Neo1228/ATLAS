package com.atlas.account.service.impl;

import com.atlas.account.model.AccountActionTokenPurpose;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountEmailSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 이메일 확인·비밀번호 재설정 토큰을 발급하고 목적에 맞는 메일로 보낸다. 요청 경로(현행)와 outbox worker가 같이 쓴다.
 *
 * <p>평문 토큰은 반환값과 메일 본문에만 있고 DB에는 SHA-256 해시만 저장한다. 발급은 같은 목적의 이전 토큰을 소비 처리한다.
 */
final class ActionTokenIssuer {
    private static final Duration EMAIL_VERIFICATION_LIFETIME = Duration.ofHours(24);
    private static final Duration PASSWORD_RESET_LIFETIME = Duration.ofMinutes(30);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final AccountRepository accounts;
    private final Clock clock;

    ActionTokenIssuer(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    /** 호출자의 transaction 안에서 새 토큰을 발급한다. */
    String issue(UUID accountId, AccountActionTokenPurpose purpose) {
        String token = newSecret();
        Instant now = clock.instant();
        Duration lifetime = switch (purpose) {
            case EMAIL_VERIFICATION -> EMAIL_VERIFICATION_LIFETIME;
            case PASSWORD_RESET -> PASSWORD_RESET_LIFETIME;
        };
        accounts.replaceActionToken(accountId, purpose, sha256Hex(token), now, now.plus(lifetime));
        return token;
    }

    static void send(AccountEmailSender sender, AccountActionTokenPurpose purpose, String email, String token) {
        switch (purpose) {
            case EMAIL_VERIFICATION -> sender.sendVerification(email, token);
            case PASSWORD_RESET -> sender.sendPasswordReset(email, token);
        }
    }

    static String newSecret() {
        byte[] value = new byte[48];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
