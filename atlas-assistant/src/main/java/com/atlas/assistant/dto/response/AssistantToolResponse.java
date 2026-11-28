package com.atlas.assistant.dto.response;

import java.util.Map;

/** MCP {@code tools/list}의 Tool 객체와 같은 모양(name, description, inputSchema). */
public record AssistantToolResponse(String name, String description, Map<String, Object> inputSchema) {
    public AssistantToolResponse {
        inputSchema = Map.copyOf(inputSchema);
    }
}
