package com.atlas.account.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccountRequest {
    private AccountRequest() {
    }

    public record AccountActionToken(
            @NotBlank @Size(max = 128) String token) {
    }

    /**
     * 계정 폐쇄 요청입니다.
     *
     * <p>비밀번호를 다시 요구하는 이유: 액세스 토큰만으로 폐쇄를 허용하면 탈취된 기기나
     * 짧은 세션에서 실수·악의로 계정을 닫을 수 있습니다. 되돌릴 수 없는 작업이므로
     * 본인 확인을 한 번 더 받습니다.
     */
    public record CloseAccount(
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    public record Login(@NotBlank @Email @Size(max = 320) String email,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record MfaChallenge(
            @NotBlank @Size(max = 128) String challengeToken,
            @Pattern(regexp = "^$|^[0-9]{6}$") String code,
            @Size(max = 64) String recoveryCode) {
    }

    public record MfaCode(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    public record PasswordResetConfirm(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }

    public record PasswordReset(
            @NotBlank @Email @Size(max = 320) String email) {
    }

    public record RefreshToken(@NotBlank @Size(max = 128) String refreshToken) {
    }

    public record RegisterBuyer(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    /**
     * 운영자 계정 정지·해제 요청입니다.
     *
     * <p>사유를 필수로 요구합니다. 감사 기록에 "왜"가 없으면 나중에 판단을 검증할 수
     * 없고, 기존 결제 복구 운영자 API 도 같은 규칙을 씁니다.
     */
    public record SuspendAccount(
            @NotBlank @Size(max = 500) String reason) {
    }
}
