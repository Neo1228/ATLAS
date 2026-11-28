package com.atlas.ai.provider;

import com.atlas.ai.dto.request.ChatCompletionRequest;
import com.atlas.ai.dto.response.ChatCompletionResponse;
import com.atlas.ai.exception.LlmException;
import com.atlas.ai.model.ChatRole;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 {@code POST /chat/completions} 한 번을 보내고 응답을 해석한다. 재시도와 예산은
 * {@code ChatCompletionServiceImpl}이 맡는다. 오류 메시지에는 상태 코드만 남기고 응답 본문과 API key는 남기지 않는다
 * (공급자 오류 본문이 프롬프트 일부를 되돌려 주는 경우가 있다).
 */
public class OpenAiChatClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String model;

    public OpenAiChatClient(RestClient.Builder builder, ObjectMapper mapper, String baseUrl, String apiKey,
                            String model, Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        RestClient.Builder configured = builder.clone().baseUrl(baseUrl).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.client = configured.build();
        this.mapper = Objects.requireNonNull(mapper);
        this.model = Objects.requireNonNull(model);
    }

    /** 재시도해도 되는 실패는 {@code UNAVAILABLE}, 아니면 다른 코드로 던진다. */
    public ChatCompletionResponse send(ChatCompletionRequest request, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages().stream().map(OpenAiChatClient::wire).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(OpenAiChatClient::wire).toList());
            body.put("tool_choice", "auto");
        }
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0);
        String raw;
        try {
            raw = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body)).retrieve().body(String.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 429 || status == 502 || status == 503 || status == 504) {
                throw new LlmException(LlmException.Code.UNAVAILABLE, "LLM provider returned HTTP " + status);
            }
            throw new LlmException(LlmException.Code.INVALID_RESPONSE, "LLM provider rejected the request: HTTP " + status);
        } catch (ResourceAccessException exception) {
            if (causedByTimeout(exception)) {
                throw new LlmException(LlmException.Code.TIMEOUT, "LLM provider did not answer in time");
            }
            throw new LlmException(LlmException.Code.UNAVAILABLE, "LLM provider is unreachable");
        } catch (RestClientException exception) {
            throw new LlmException(LlmException.Code.INVALID_RESPONSE, "LLM provider call failed");
        }
        return parse(raw);
    }

    private ChatCompletionResponse parse(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(raw == null ? "" : raw);
        } catch (JacksonException exception) {
            throw new LlmException(LlmException.Code.INVALID_RESPONSE, "LLM response is not JSON");
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw new LlmException(LlmException.Code.INVALID_RESPONSE, "LLM response has no message");
        }
        List<ChatCompletionRequest.ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            calls.add(new ChatCompletionRequest.ToolCall(text(call.path("id")), text(function.path("name")),
                    text(function.path("arguments"))));
        }
        JsonNode usage = root.path("usage");
        ChatCompletionResponse.Usage tokens = usage.isObject()
                ? new ChatCompletionResponse.Usage(usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0))
                : null;
        return new ChatCompletionResponse(ChatCompletionRequest.Message.assistant(text(message.path("content")), calls),
                text(choice.path("finish_reason")), tokens);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static Map<String, Object> wire(ChatCompletionRequest.Message message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role().name().toLowerCase(Locale.ROOT));
        // tool_calls만 있는 assistant 메시지도 content 키를 둔다(일부 호환 서버가 키 누락을 거절한다).
        wire.put("content", message.content() == null ? "" : message.content());
        if (message.role() == ChatRole.ASSISTANT && !message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(), "type", "function",
                    "function", Map.of("name", call.name(),
                            "arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson()))).toList());
        }
        if (message.role() == ChatRole.TOOL) wire.put("tool_call_id", message.toolCallId());
        return wire;
    }

    private static Map<String, Object> wire(ChatCompletionRequest.Tool tool) {
        return Map.of("type", "function", "function", Map.of(
                "name", tool.name(), "description", tool.description(), "parameters", tool.parametersSchema()));
    }

    private static boolean causedByTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            // 연결 시간 초과는 요청이 공급자에 닿지 않았으므로 재시도 대상(UNAVAILABLE)으로 둔다.
            if (cause instanceof HttpConnectTimeoutException) return false;
            if (cause instanceof HttpTimeoutException) return true;
        }
        return false;
    }
}
