package com.atlas.llm.dto.response;

import com.atlas.llm.dto.request.ChatCompletionRequest;

public record ChatCompletionResponse(ChatCompletionRequest.Message message, String finishReason, Usage usage) {

    /** 공급자가 보고한 토큰 수. 보고가 없으면 서비스가 보수적으로 추정한 값이다. */
    public record Usage(long promptTokens, long completionTokens) {
        public long total() {
            return promptTokens + completionTokens;
        }
    }
}
