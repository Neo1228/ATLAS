package com.atlas.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.ai.dto.request.ChatCompletionRequest;
import com.atlas.ai.dto.response.ChatCompletionResponse;
import com.atlas.ai.exception.LlmException;
import com.atlas.ai.provider.OpenAiChatClient;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ChatCompletionServiceImplTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private ChatCompletionServiceImpl service(Duration readTimeout) {
        return new ChatCompletionServiceImpl(new OpenAiChatClient(RestClient.builder(), MAPPER,
                server.url("/v1").toString(), "test-key", "test-model", Duration.ofSeconds(1), readTimeout),
                2, Duration.ZERO);
    }

    private static ChatCompletionRequest request(long remainingBudget) {
        return new ChatCompletionRequest(List.of(ChatCompletionRequest.Message.system("s"), ChatCompletionRequest.Message.user("러닝화 찾아줘")),
                List.of(new ChatCompletionRequest.Tool("search_products", "search",
                        Map.of("type", "object", "properties", Map.of()))), 600, remainingBudget);
    }

    @Test
    void sendsToolsAndClampsResponseTokensToRemainingBudgetThenParsesToolCalls() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"search_products","arguments":"{\\"query\\":\\"러닝화\\"}"}}]}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":15}}
                """));

        ChatCompletionResponse response = service(Duration.ofSeconds(2)).complete(request(250));

        assertThat(response.message().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("search_products");
            assertThat(call.argumentsJson()).isEqualTo("{\"query\":\"러닝화\"}");
        });
        assertThat(response.usage().total()).isEqualTo(135);
        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer test-key");
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(250);
        assertThat(body.path("tools").path(0).path("function").path("name").asString()).isEqualTo("search_products");
    }

    @Test
    void retriesUnavailableProviderButNotReadTimeoutBecauseTheProviderMayHaveBilledIt() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));
        assertThat(service(Duration.ofSeconds(2)).complete(request(1000)).message().content()).isEqualTo("ok");
        assertThat(server.getRequestCount()).isEqualTo(2);

        server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS));
        assertThatThrownBy(() -> service(Duration.ofMillis(200)).complete(request(1000)))
                .isInstanceOfSatisfying(LlmException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LlmException.Code.TIMEOUT));
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void exhaustedBudgetIsRefusedWithoutCallingTheProvider() {
        assertThatThrownBy(() -> service(Duration.ofSeconds(2)).complete(request(0)))
                .isInstanceOfSatisfying(LlmException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LlmException.Code.BUDGET_EXHAUSTED));
        assertThat(server.getRequestCount()).isZero();
    }
}
