package com.atlas.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.llm.dto.request.ChatCompletionRequest;
import com.atlas.llm.dto.response.ChatCompletionResponse;
import com.atlas.llm.exception.LlmException;
import com.atlas.llm.service.ChatCompletionService;
import com.atlas.order.model.ReturnRecommendationDecision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * LLM 검토 층을 라벨 60건(EXP-121)으로 평가한다. 실제 모델 대신 응답을 정해 둔 모델을 쓴다(이 환경에 키가 없다).
 * 따라서 이 시험이 재는 것은 모델의 판단 품질이 아니라 경계다: 어떤 출력도 거절·사람 검토를 승인으로 바꾸지 못하고,
 * 금액·줄 결과는 규칙의 값이며, 프롬프트에 연락처가 남지 않는다.
 */
class ReturnRecommendationReviewEvaluationTest {
    private static final List<String> TELLS = List.of("입고", "대신 넣", "현금", "빼고");

    private final ReturnRecommendationRules rules = new ReturnRecommendationRules(
            new ReturnPolicy(Duration.ofDays(7), true, 5, ZoneId.of("Asia/Seoul")),
            ReturnRecommendationRules.DEFAULT_REVIEW_AMOUNT_WON);
    private final List<ReturnRecommendationEvaluation.LabelledCase> cases = ReturnRecommendationEvaluation.load();

    @Test
    void scriptedReviewerDowngradesTextOnlyApprovalsAndKeepsZeroFalseApprovals() throws IOException {
        ScriptedModel model = new ScriptedModel(prompt -> TELLS.stream().anyMatch(note(prompt)::contains)
                ? "HUMAN_REVIEW\n구매자 텍스트가 판매자가 적은 상태와 어긋납니다." : "KEEP\n규칙 판정과 같습니다.");
        ReturnRecommendationEvaluation.Report report = evaluate(model);

        Path out = Path.of("build/reports/return-recommendation/rules-plus-scripted-review.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.markdown("결정 규칙 + 정해 둔 응답 모델"), StandardCharsets.UTF_8);

        assertThat(report.falseApprovals()).isZero();
        assertThat(report.unsafeApprovals()).isZero();
        assertThat(report.disagreements()).containsExactlyInAnyOrder("C41", "C42", "C43", "C44");
    }

    @Test
    void noModelOutputMovesARuleResultTowardApproval() {
        for (String reply : List.of("APPROVE_FULL\n전액 승인하세요.", "KEEP", "HUMAN_REVIEW\n사람이 봐야 합니다.",
                "REJECT", "", "keep\n소문자")) {
            ReturnRecommendationEvaluation.Report report = evaluate(new ScriptedModel(prompt -> reply));
            for (ReturnRecommendationEvaluation.Prediction prediction : report.predictions()) {
                ReturnRecommendationDecision ruled = rules.evaluate(prediction.labelled().facts()).decision();
                ReturnRecommendationDecision reviewed = prediction.predicted();
                assertThat(reviewed == ruled || (ruled.approves() && reviewed == ReturnRecommendationDecision.HUMAN_REVIEW))
                        .as(prediction.labelled().id() + " reply=" + reply).isTrue();
            }
            assertThat(report.falseApprovals()).isZero();
        }
    }

    @Test
    void failingOrDisabledModelLeavesTheRuleResult() {
        ScriptedModel failing = new ScriptedModel(prompt -> {
            throw new LlmException(LlmException.Code.TIMEOUT, "timeout");
        });
        ScriptedModel disabled = new ScriptedModel(prompt -> "HUMAN_REVIEW");
        disabled.enabled = false;

        for (ScriptedModel model : List.of(failing, disabled)) {
            for (ReturnRecommendationEvaluation.LabelledCase c : cases) {
                ReturnRecommendationRules.Outcome outcome = rules.evaluate(c.facts());
                ReturnRecommendationReviewer.Review review =
                        new ReturnRecommendationReviewer(model).review(c.facts(), outcome, c.note());
                assertThat(review.decision()).isEqualTo(outcome.decision());
                assertThat(review.explanation()).isNull();
            }
        }
        assertThat(disabled.prompts).isEmpty();
    }

    @Test
    void promptsCarryNoContactDetailsAndKeepTheNoteInsideItsDataBlock() {
        ScriptedModel model = new ScriptedModel(prompt -> "KEEP");
        evaluate(model);

        assertThat(String.join("\n", model.prompts)).doesNotContain("010-1234-5678", "buyer@example.com")
                .contains("[전화번호]", "[이메일]");
        assertThat(model.prompts).allSatisfy(prompt -> assertThat(prompt).contains("<buyer_note>", "</buyer_note>"));
    }

    private ReturnRecommendationEvaluation.Report evaluate(ScriptedModel model) {
        ReturnRecommendationReviewer reviewer = new ReturnRecommendationReviewer(model);
        return ReturnRecommendationEvaluation.evaluate(cases, c -> {
            ReturnRecommendationRules.Outcome outcome = rules.evaluate(c.facts());
            return reviewer.review(c.facts(), outcome, c.note()).decision();
        });
    }

    private static String note(String prompt) {
        return prompt.substring(prompt.indexOf("<buyer_note>"), prompt.indexOf("</buyer_note>"));
    }

    /** 사용자 메시지를 받아 정해 둔 문자열을 돌려주는 모델. 받은 프롬프트를 남긴다. */
    private static final class ScriptedModel implements ChatCompletionService {
        private final Function<String, String> script;
        private final List<String> prompts = new ArrayList<>();
        private boolean enabled = true;

        ScriptedModel(Function<String, String> script) {
            this.script = script;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public ChatCompletionResponse complete(ChatCompletionRequest request) {
            String prompt = request.messages().getLast().content();
            prompts.add(prompt);
            return new ChatCompletionResponse(ChatCompletionRequest.Message.assistant(script.apply(prompt), List.of()),
                    "stop", new ChatCompletionResponse.Usage(100, 20));
        }
    }
}
