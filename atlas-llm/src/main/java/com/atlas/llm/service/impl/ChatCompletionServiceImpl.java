package com.atlas.llm.service.impl;

import com.atlas.llm.dto.request.ChatCompletionRequest;
import com.atlas.llm.dto.response.ChatCompletionResponse;
import com.atlas.llm.exception.LlmException;
import com.atlas.llm.provider.OpenAiChatClient;
import com.atlas.llm.service.ChatCompletionService;
import java.time.Duration;

/**
 * 예산, 재시도, 활성화 여부를 적용한다. HTTP 형식은 {@link OpenAiChatClient}가 맡는다.
 *
 * <p>재시도는 요청이 공급자에 닿지 않았거나 공급자가 처리하지 않았다고 밝힌 경우(연결 실패, 429, 502·503·504)만 한다.
 * 동기 HTTP 요청 안에서 호출되므로 대기는 짧게 둔다.
 */
public class ChatCompletionServiceImpl implements ChatCompletionService {
    private final OpenAiChatClient client;
    private final int maxAttempts;
    private final Duration retryBackoff;

    /** 꺼진 서비스. */
    public ChatCompletionServiceImpl() {
        this(null, 1, Duration.ZERO);
    }

    public ChatCompletionServiceImpl(OpenAiChatClient client, int maxAttempts, Duration retryBackoff) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.client = client;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = retryBackoff;
    }

    @Override
    public boolean enabled() {
        return client != null;
    }

    @Override
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        if (client == null) throw new LlmException(LlmException.Code.DISABLED, "LLM is disabled");
        if (request.remainingTokenBudget() <= 0) {
            throw new LlmException(LlmException.Code.BUDGET_EXHAUSTED, "Conversation token budget is exhausted");
        }
        int maxTokens = (int) Math.max(1, Math.min(request.maxCompletionTokens(), request.remainingTokenBudget()));
        LlmException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatCompletionResponse response = client.send(request, maxTokens);
                return response.usage() != null ? response
                        : new ChatCompletionResponse(response.message(), response.finishReason(),
                                estimate(request, maxTokens));
            } catch (LlmException failure) {
                if (failure.code() != LlmException.Code.UNAVAILABLE) throw failure;
                last = failure;
                if (attempt < maxAttempts) pause(attempt);
            }
        }
        throw last;
    }

    /**
     * 공급자가 usage를 보내지 않으면 상한을 지키기 위해 과대 추정한다. 프롬프트는 문자 1개를 1토큰으로
     * (영문은 대개 4문자당 1토큰, 한글은 음절당 1토큰 안팎), 응답은 허용한 최대치로 센다.
     */
    private static ChatCompletionResponse.Usage estimate(ChatCompletionRequest request, int maxTokens) {
        long chars = 0;
        for (ChatCompletionRequest.Message message : request.messages()) {
            chars += message.content() == null ? 0 : message.content().length();
        }
        return new ChatCompletionResponse.Usage(chars, maxTokens);
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(retryBackoff.multipliedBy(attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Code.UNAVAILABLE, "Interrupted while retrying the LLM call");
        }
    }
}
