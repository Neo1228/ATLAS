package com.atlas.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 운영자 계정 정지·해제 요청입니다.
 *
 * <p>사유를 필수로 요구합니다. 감사 기록에 "왜"가 없으면 나중에 판단을 검증할 수
 * 없고, 기존 결제 복구 운영자 API 도 같은 규칙을 씁니다.
 */
public record SuspendAccountRequest(
        @NotBlank @Size(max = 500) String reason) {
}
