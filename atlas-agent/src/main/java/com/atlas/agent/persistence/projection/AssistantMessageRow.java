package com.atlas.agent.persistence.projection;

/** 저장된 대화 메시지. {@code toolCallsJson}은 assistant 메시지가 요청한 도구 호출 목록(JSON 배열)이다. */
public record AssistantMessageRow(String role, String content, String toolCallsJson, String toolCallId) {
}
