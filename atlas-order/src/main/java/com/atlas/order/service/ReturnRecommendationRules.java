package com.atlas.order.service;

import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.ReturnItemCondition;
import com.atlas.order.model.ReturnReason;
import com.atlas.order.model.ReturnRecommendationDecision;
import com.atlas.order.model.ReturnRecommendationReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 판매자 반품 권고의 결정 규칙(ADR-0110). 입력은 DB에서 읽은 사실과 판매자가 적은 줄 상태뿐이고, 구매자의
 * 자유 텍스트는 {@link Facts}에 없으므로 규칙에 닿지 않는다. 같은 입력이면 항상 같은 결과다.
 *
 * <p>줄 판정 순서: 수량 초과(이미 환불) → 미반환 → 출고 시각 없음 → 반품 기간 → 판매자 확인 하자 → 상태와 사유.
 * 한 줄이라도 이미 환불된 수량과 겹치면 반품 전체를 거절한다. 그 밖에는 줄 결과를 모아 전액·부분·거절을 정하고,
 * 판단하지 못한 줄이 있으면 사람 검토로 둔다. 승인 권고의 환불 합계가 상한을 넘으면 사람 검토로 바꾼다.
 *
 * <p>반품 기간은 요청 시각을 그 줄 출고의 {@link ReturnPolicy#returnWindowEndsAt}과 비교한다(경계 포함).
 * {@code window-enforced=false}로 요청 시점 검사를 꺼도 권고는 기간을 본다. 끄는 것은 접수 거절을 멈추는
 * 롤백 스위치이고 기간 밖 환불을 승인하라는 뜻이 아니기 때문이다.
 */
@Component
public record ReturnRecommendationRules(
        ReturnPolicy policy,
        @Value("${atlas.order.return.recommendation.review-amount-won:"
                + ReturnRecommendationRules.DEFAULT_REVIEW_AMOUNT_WON + "}") long reviewAmountWon) {
    /** 자동 승인 권고 상한(원, 포함). 근거 있는 값이 아니라 가정이며 설정으로 바꾼다(ADR-0110). */
    public static final long DEFAULT_REVIEW_AMOUNT_WON = 300_000L;

    public ReturnRecommendationRules {
        Objects.requireNonNull(policy, "policy");
        if (reviewAmountWon < 1) throw new IllegalArgumentException("review amount must be positive");
    }

    /** 반품 한 건의 사실. {@code requestedAt}은 반품 요청 시각, 사유가 없던 시절의 행은 {@code reason}이 null이다. */
    public record Facts(ReturnReason reason, Instant requestedAt, List<Line> lines) {
        public Facts {
            Objects.requireNonNull(requestedAt, "requestedAt");
            lines = List.copyOf(lines);
            if (lines.isEmpty()) throw new IllegalArgumentException("return lines are required");
        }
    }

    /**
     * 반품 줄의 사실. {@code refundedQuantity}는 이 주문 줄에 이미 기록된 환불 수량, {@code refundAmountWon}은
     * 서버가 주문 스냅샷으로 계산한 이 줄의 환불액(수량이 남지 않아 계산할 수 없으면 0), {@code deliveredAt}은
     * 이 줄 출고의 배송 완료 시각(모르면 null), {@code condition}은 판매자가 적은 상태다.
     */
    public record Line(UUID orderItemId, int quantity, int orderedQuantity, int refundedQuantity,
                       long refundAmountWon, Instant deliveredAt, ReturnItemCondition condition) {
        public Line {
            Objects.requireNonNull(orderItemId, "orderItemId");
            Objects.requireNonNull(condition, "condition");
        }
    }

    /** 줄 권고. {@code suggestedResult}가 null이면 사람이 판단할 줄이다. 환불액은 {@code PASSED}일 때만 있다. */
    public record LineOutcome(UUID orderItemId, OrderReturnInspectionResult suggestedResult, Long refundAmountWon,
                              Instant returnWindowEndsAt, ReturnRecommendationReason reason) { }

    public record Outcome(ReturnRecommendationDecision decision, List<ReturnRecommendationReason> reasons,
                          long refundAmountWon, List<LineOutcome> lines) {
        public Outcome {
            reasons = List.copyOf(reasons);
            lines = List.copyOf(lines);
        }
    }

    public Outcome evaluate(Facts facts) {
        List<LineOutcome> lines = new ArrayList<>();
        boolean duplicate = false;
        for (Line line : facts.lines()) {
            Instant windowEndsAt = line.deliveredAt() == null ? null : policy.returnWindowEndsAt(line.deliveredAt());
            if (exceedsRefundable(line)) {
                duplicate = true;
                lines.add(new LineOutcome(line.orderItemId(), OrderReturnInspectionResult.FAILED, null, windowEndsAt,
                        ReturnRecommendationReason.DUPLICATE_REFUND));
            } else {
                lines.add(judge(facts, line, windowEndsAt));
            }
        }
        if (duplicate) {
            // 이미 나간 돈과 겹치는 반품은 다른 줄이 정상이어도 데이터 이상이므로 전체를 거절한다.
            List<LineOutcome> rejected = lines.stream()
                    .map(line -> new LineOutcome(line.orderItemId(), OrderReturnInspectionResult.FAILED, null,
                            line.returnWindowEndsAt(), line.reason()))
                    .toList();
            return new Outcome(ReturnRecommendationDecision.REJECT, reasonsOf(rejected), 0L, rejected);
        }

        long amount = 0;
        boolean anyPassed = false;
        boolean anyFailed = false;
        boolean anyUndecided = false;
        for (LineOutcome line : lines) {
            if (line.suggestedResult() == null) {
                anyUndecided = true;
            } else if (line.suggestedResult() == OrderReturnInspectionResult.PASSED) {
                anyPassed = true;
                amount = Math.addExact(amount, line.refundAmountWon());
            } else {
                anyFailed = true;
            }
        }
        List<ReturnRecommendationReason> reasons = reasonsOf(lines);
        ReturnRecommendationDecision decision;
        if (anyUndecided) {
            decision = ReturnRecommendationDecision.HUMAN_REVIEW;
        } else if (!anyPassed) {
            decision = ReturnRecommendationDecision.REJECT;
        } else {
            decision = anyFailed ? ReturnRecommendationDecision.APPROVE_PARTIAL : ReturnRecommendationDecision.APPROVE_FULL;
        }
        if (amount > reviewAmountWon) {
            // 승인 쪽 금액이 상한을 넘으면 사람이 본다. 거절과 사람 검토는 금액과 무관하게 그대로 둔다.
            reasons = new ArrayList<>(reasons);
            reasons.add(ReturnRecommendationReason.AMOUNT_ABOVE_REVIEW_LIMIT);
            if (decision.approves()) decision = ReturnRecommendationDecision.HUMAN_REVIEW;
        }
        return new Outcome(decision, reasons, amount, lines);
    }

    private static boolean exceedsRefundable(Line line) {
        return line.quantity() <= 0 || line.refundedQuantity() < 0
                || line.quantity() > line.orderedQuantity() - line.refundedQuantity()
                || line.refundAmountWon() <= 0;
    }

    private static LineOutcome judge(Facts facts, Line line, Instant windowEndsAt) {
        ReturnItemCondition condition = line.condition();
        if (condition == ReturnItemCondition.NOT_RETURNED) {
            return failed(line, windowEndsAt, ReturnRecommendationReason.ITEM_NOT_RETURNED);
        }
        if (windowEndsAt == null) {
            return undecided(line, null, ReturnRecommendationReason.DELIVERY_TIME_UNKNOWN);
        }
        if (facts.requestedAt().isAfter(windowEndsAt)) {
            return condition == ReturnItemCondition.DEFECT_CONFIRMED
                    ? undecided(line, windowEndsAt, ReturnRecommendationReason.OUTSIDE_WINDOW_DEFECT)
                    : failed(line, windowEndsAt, ReturnRecommendationReason.OUTSIDE_RETURN_WINDOW);
        }
        return switch (condition) {
            case DEFECT_CONFIRMED -> passed(line, windowEndsAt, ReturnRecommendationReason.DEFECT_CONFIRMED);
            case UNOPENED, OPENED -> passed(line, windowEndsAt, ReturnRecommendationReason.RESELLABLE_CONDITION);
            case USED, DAMAGED -> facts.reason() == ReturnReason.CHANGED_MIND
                    ? failed(line, windowEndsAt, ReturnRecommendationReason.BUYER_CAUSED_WEAR)
                    : undecided(line, windowEndsAt, ReturnRecommendationReason.CLAIM_NEEDS_JUDGMENT);
            case NOT_RETURNED -> throw new IllegalStateException("handled above");
        };
    }

    private static List<ReturnRecommendationReason> reasonsOf(List<LineOutcome> lines) {
        EnumSet<ReturnRecommendationReason> reasons = EnumSet.noneOf(ReturnRecommendationReason.class);
        lines.forEach(line -> reasons.add(line.reason()));
        return List.copyOf(reasons);
    }

    private static LineOutcome passed(Line line, Instant windowEndsAt, ReturnRecommendationReason reason) {
        return new LineOutcome(line.orderItemId(), OrderReturnInspectionResult.PASSED, line.refundAmountWon(),
                windowEndsAt, reason);
    }

    private static LineOutcome failed(Line line, Instant windowEndsAt, ReturnRecommendationReason reason) {
        return new LineOutcome(line.orderItemId(), OrderReturnInspectionResult.FAILED, null, windowEndsAt, reason);
    }

    private static LineOutcome undecided(Line line, Instant windowEndsAt, ReturnRecommendationReason reason) {
        return new LineOutcome(line.orderItemId(), null, null, windowEndsAt, reason);
    }
}
