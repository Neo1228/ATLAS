package com.atlas.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.llm.dto.request.ChatCompletionRequest;
import com.atlas.llm.dto.response.ChatCompletionResponse;
import com.atlas.llm.provider.OpenAiChatClient;
import com.atlas.llm.service.ChatCompletionService;
import com.atlas.llm.service.impl.ChatCompletionServiceImpl;
import com.atlas.order.model.ReturnRecommendationDecision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 반품 권고 LLM 검토 층을 실제 모델로 라벨 60건에 돌리는 평가 하네스(EXP-122). 기본 {@code check}에서는 건너뛴다.
 * 운영과 같은 {@link OpenAiChatClient}·{@link ChatCompletionServiceImpl}을 {@code ATLAS_LLM_*} 환경 변수로 만든다.
 *
 * <pre>
 * ATLAS_LLM_LIVE_EVAL=true ATLAS_LLM_BASE_URL=http://127.0.0.1:11434/v1 ATLAS_LLM_MODEL=qwen2.5:3b \
 * ./gradlew :atlas-order:test --tests '*ReturnRecommendationLiveReviewEvaluation'
 * </pre>
 *
 * <p>단정은 "승인 쪽으로 옮긴 건수 0"만 한다(구조상 0이어야 하는 값을 실측으로 확인). 사람 검토 비율, 첫 줄 형식 준수,
 * 설명은 {@code build/reports/return-recommendation/}에 남겨 문서에서 읽는다.
 */
@EnabledIfEnvironmentVariable(named = "ATLAS_LLM_LIVE_EVAL", matches = "true")
class ReturnRecommendationLiveReviewEvaluation {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ReturnRecommendationRules rules = new ReturnRecommendationRules(
            new ReturnPolicy(Duration.ofDays(7), true, 5, ZoneId.of("Asia/Seoul")),
            ReturnRecommendationRules.DEFAULT_REVIEW_AMOUNT_WON);

    @Test
    void liveReviewerNeverMovesARuleResultTowardApproval() throws IOException {
        String model = System.getenv("ATLAS_LLM_MODEL");
        Recording llm = new Recording(new ChatCompletionServiceImpl(new OpenAiChatClient(RestClient.builder(), JSON,
                System.getenv("ATLAS_LLM_BASE_URL"), System.getenv("ATLAS_LLM_API_KEY"), model,
                Duration.ofSeconds(2), Duration.ofSeconds(120)), 2, Duration.ofMillis(300)));
        ReturnRecommendationReviewer reviewer = new ReturnRecommendationReviewer(llm, true);

        StringBuilder lines = new StringBuilder();
        int movedTowardApproval = 0;
        List<ReturnRecommendationEvaluation.LabelledCase> cases = ReturnRecommendationEvaluation.load();
        Map<String, ReturnRecommendationDecision> reviewed = new LinkedHashMap<>();
        for (ReturnRecommendationEvaluation.LabelledCase c : cases) {
            ReturnRecommendationRules.Outcome outcome = rules.evaluate(c.facts());
            llm.last = null;
            long started = System.currentTimeMillis();
            ReturnRecommendationReviewer.Review review = reviewer.review(c.facts(), outcome, c.note());
            long latency = System.currentTimeMillis() - started;
            if (towardApproval(outcome.decision(), review.decision())) movedTowardApproval++;
            reviewed.put(c.id(), review.decision());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", c.id());
            record.put("label", c.label().name());
            record.put("ruled", outcome.decision().name());
            record.put("reviewed", review.decision().name());
            record.put("downgraded", review.downgraded());
            record.put("explanation", review.explanation());
            record.put("raw", llm.last);
            record.put("latencyMs", latency);
            lines.append(JSON.writeValueAsString(record)).append('\n');
        }
        ReturnRecommendationEvaluation.Report report = ReturnRecommendationEvaluation.evaluate(cases,
                c -> reviewed.get(c.id()));

        Path dir = Path.of("build/reports/return-recommendation");
        Files.createDirectories(dir);
        String name = "live-" + model.replaceAll("[^A-Za-z0-9.]", "_");
        Files.writeString(dir.resolve(name + ".jsonl"), lines.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(name + ".md"), report.markdown("결정 규칙 + " + model), StandardCharsets.UTF_8);
        System.out.printf("live review model=%s moved-toward-approval=%d false-approvals=%d human-review=%d/%d%n",
                model, movedTowardApproval, report.falseApprovals(), report.humanReviews(), report.total());

        assertThat(movedTowardApproval).isZero();
        assertThat(report.falseApprovals()).isZero();
    }

    /** 승인 쪽 순서: REJECT·HUMAN_REVIEW < APPROVE_PARTIAL < APPROVE_FULL. */
    private static boolean towardApproval(ReturnRecommendationDecision ruled, ReturnRecommendationDecision reviewed) {
        return rank(reviewed) > rank(ruled);
    }

    private static int rank(ReturnRecommendationDecision decision) {
        return switch (decision) {
            case APPROVE_FULL -> 2;
            case APPROVE_PARTIAL -> 1;
            default -> 0;
        };
    }

    /** 모델 원문(첫 줄 형식 확인용)을 남긴다. */
    private static final class Recording implements ChatCompletionService {
        private final ChatCompletionService delegate;
        private String last;

        Recording(ChatCompletionService delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean enabled() {
            return delegate.enabled();
        }

        @Override
        public ChatCompletionResponse complete(ChatCompletionRequest request) {
            ChatCompletionResponse response = delegate.complete(request);
            last = response.message().content();
            return response;
        }
    }
}
