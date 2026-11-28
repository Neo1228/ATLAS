package com.atlas.ai.service;

import com.atlas.ai.dto.request.ChatCompletionRequest;
import com.atlas.ai.dto.response.ChatCompletionResponse;

/**
 * OpenAI 호환 chat completions 호출 계약(ADR-0109).
 *
 * <ul>
 *   <li>꺼져 있으면 {@link #complete}가 {@code DISABLED}를 던진다. 호출자는 LLM 없이 동작하는 경로를 유지한다.</li>
 *   <li>모델 출력(본문, 도구 인자)은 신뢰하지 않는 입력이다. 이 계약은 출력의 형식만 보장하고 의미는 검증하지 않는다.</li>
 *   <li>연결 실패, 429, 502·503·504만 재시도한다. 응답 시간 초과는 공급자가 이미 과금했을 수 있어 재시도하지 않는다.</li>
 *   <li>API key는 요청 헤더에만 쓰고 로그와 예외 메시지에 남기지 않는다.</li>
 * </ul>
 */
public interface ChatCompletionService {
    boolean enabled();

    ChatCompletionResponse complete(ChatCompletionRequest request);
}
