package com.atlas.ai.config;

import com.atlas.ai.provider.OpenAiChatClient;
import com.atlas.ai.service.ChatCompletionService;
import com.atlas.ai.service.impl.ChatCompletionServiceImpl;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM 클라이언트 조립. 기본은 꺼짐이다. 켜려면 {@code ATLAS_LLM_ENABLED=true}와 base URL, model을 준다.
 * API key는 환경 변수로만 받고 설정 파일과 로그에 쓰지 않는다. key가 없는 로컬 OpenAI 호환 서버도 쓸 수 있게
 * key는 선택이다.
 */
@Configuration(proxyBeanMethods = false)
public class LlmConfiguration {

    @Bean
    ChatCompletionService chatCompletionService(RestClient.Builder builder, ObjectMapper mapper,
            @Value("${atlas.llm.enabled:false}") boolean enabled,
            @Value("${atlas.llm.base-url:}") String baseUrl,
            @Value("${atlas.llm.api-key:}") String apiKey,
            @Value("${atlas.llm.model:}") String model,
            @Value("${atlas.llm.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${atlas.llm.read-timeout:PT30S}") Duration readTimeout,
            @Value("${atlas.llm.max-attempts:2}") int maxAttempts,
            @Value("${atlas.llm.retry-backoff:PT0.3S}") Duration retryBackoff) {
        if (!enabled) return new ChatCompletionServiceImpl();
        if (baseUrl.isBlank() || model.isBlank()) {
            throw new IllegalStateException("atlas.llm.enabled=true requires atlas.llm.base-url and atlas.llm.model");
        }
        return new ChatCompletionServiceImpl(
                new OpenAiChatClient(builder, mapper, baseUrl, apiKey, model, connectTimeout, readTimeout),
                maxAttempts, retryBackoff);
    }
}
