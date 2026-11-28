package com.atlas.assistant.dto.response;

import com.atlas.assistant.model.ToolCallOutcome;

/** 이번 차례에 모델이 요청한 도구 호출 하나와 서버 판정. {@code reason}은 거절 사유만 담는다. */
public record AssistantToolCallSummary(String tool, ToolCallOutcome outcome, String reason) {
}
