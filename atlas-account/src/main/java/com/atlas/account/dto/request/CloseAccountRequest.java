package com.atlas.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 계정 폐쇄 요청입니다.
 *
 * <p>비밀번호를 다시 요구하는 이유: 액세스 토큰만으로 폐쇄를 허용하면 탈취된 기기나
 * 짧은 세션에서 실수·악의로 계정을 닫을 수 있습니다. 되돌릴 수 없는 작업이므로
 * 본인 확인을 한 번 더 받습니다.
 */
public record CloseAccountRequest(
        @NotBlank @Size(min = 12, max = 128) String password) {
}
