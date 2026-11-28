package com.atlas.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.ReturnRecommendationDecision;
import com.atlas.order.model.ReturnRecommendationReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 결정 규칙을 라벨 60건으로 평가한다(EXP-121). 핵심 지표 거짓 승인은 0이어야 하고, 라벨과 다른 사례는 자유 텍스트를
 * 읽어야만 정답을 알 수 있는 8건뿐이어야 한다. 규칙이 바뀌어 다른 사례가 어긋나면 EXP-121의 표도 함께 고친다.
 */
class ReturnRecommendationRulesEvaluationTest {
    /** 규칙은 승인했지만 텍스트가 사람 검토를 요구하는 4건, 규칙은 사람 검토지만 텍스트의 자백으로 거절이 정답인 4건. */
    private static final List<String> TEXT_ONLY_CASES =
            List.of("C38", "C39", "C41", "C42", "C43", "C44", "C55", "C60");

    private final ReturnRecommendationRules rules = new ReturnRecommendationRules(
            new ReturnPolicy(Duration.ofDays(7), true, 5, ZoneId.of("Asia/Seoul")),
            ReturnRecommendationRules.DEFAULT_REVIEW_AMOUNT_WON);

    @Test
    void rulesNeverApproveALabelledRejectAndMissOnlyTextDependentCases() throws IOException {
        List<ReturnRecommendationEvaluation.LabelledCase> cases = ReturnRecommendationEvaluation.load();
        ReturnRecommendationEvaluation.Report report = ReturnRecommendationEvaluation.evaluate(
                cases, labelled -> rules.evaluate(labelled.facts()).decision());

        Path out = Path.of("build/reports/return-recommendation/rules.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.markdown("결정 규칙만"), StandardCharsets.UTF_8);

        assertThat(cases).hasSize(60);
        assertThat(report.falseApprovals()).as("정답이 REJECT인데 승인 권고").isZero();
        assertThat(report.disagreements()).containsExactlyInAnyOrderElementsOf(TEXT_ONLY_CASES);
    }

    @Test
    void partialApprovalSuggestsInspectionLinesAndServerAmountOnlyForPassedLines() {
        ReturnRecommendationEvaluation.LabelledCase c46 = ReturnRecommendationEvaluation.load().stream()
                .filter(c -> c.id().equals("C46")).findFirst().orElseThrow();

        ReturnRecommendationRules.Outcome outcome = rules.evaluate(c46.facts());

        assertThat(outcome.decision()).isEqualTo(ReturnRecommendationDecision.APPROVE_PARTIAL);
        assertThat(outcome.refundAmountWon()).isEqualTo(280_000L);
        assertThat(outcome.lines()).extracting(ReturnRecommendationRules.LineOutcome::suggestedResult)
                .containsExactly(OrderReturnInspectionResult.PASSED, OrderReturnInspectionResult.FAILED);
        assertThat(outcome.lines()).extracting(ReturnRecommendationRules.LineOutcome::refundAmountWon)
                .containsExactly(280_000L, null);
        assertThat(outcome.reasons()).containsExactly(
                ReturnRecommendationReason.RESELLABLE_CONDITION, ReturnRecommendationReason.BUYER_CAUSED_WEAR);
    }
}
