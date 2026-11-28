package com.atlas.llm.dto.request;

import com.atlas.llm.model.ChatRole;
import java.util.List;
import java.util.Map;

/**
 * chat completions 한 번의 입력.
 *
 * <p>{@code remainingTokenBudget}은 호출자가 대화 단위로 관리하는 남은 토큰이다. 0 이하이면 호출하지 않고,
 * 응답 토큰 상한은 {@code min(maxCompletionTokens, remainingTokenBudget)}로 줄인다. 프롬프트 토큰은 호출 전에
 * 알 수 없으므로 대화 상한은 호출 한 번의 프롬프트만큼 넘을 수 있다(ADR-0109).
 */
public record ChatCompletionRequest(List<Message> messages, List<Tool> tools,
                                    int maxCompletionTokens, long remainingTokenBudget) {
    public ChatCompletionRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 대화 메시지. 도구 결과 메시지는 {@code toolCallId}로 모델의 호출과 짝을 이룬다. */
    public record Message(ChatRole role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Message system(String content) {
            return new Message(ChatRole.SYSTEM, content, List.of(), null);
        }

        public static Message user(String content) {
            return new Message(ChatRole.USER, content, List.of(), null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message(ChatRole.ASSISTANT, content, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message(ChatRole.TOOL, content, List.of(), toolCallId);
        }
    }

    /**
     * 모델이 요청한 함수 호출 하나. {@code argumentsJson}은 모델이 만든 문자열이므로 신뢰하지 않는다.
     * 호출자가 스키마와 권한을 다시 검증한 뒤에만 실행한다.
     */
    public record ToolCall(String id, String name, String argumentsJson) {
    }

    /** 모델에 노출하는 함수 하나. {@code parametersSchema}는 JSON Schema 객체다. */
    public record Tool(String name, String description, Map<String, Object> parametersSchema) {
        public Tool {
            parametersSchema = Map.copyOf(parametersSchema);
        }
    }
}
