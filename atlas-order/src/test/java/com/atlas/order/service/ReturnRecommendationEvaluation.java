package com.atlas.order.service;

import com.atlas.order.model.ReturnItemCondition;
import com.atlas.order.model.ReturnReason;
import com.atlas.order.model.ReturnRecommendationDecision;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 반품 권고 오프라인 평가기(EXP-121). 라벨 파일을 읽어 권고기(규칙만, 또는 규칙 + LLM)를 돌리고 4분류 정확도,
 * 매크로 F1, 핵심 지표인 거짓 승인(정답이 거절인데 승인 권고), 사람 검토 이관 비율을 센다.
 */
final class ReturnRecommendationEvaluation {
    static final Instant DELIVERED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final String CASES = "/return-recommendation-cases.txt";

    private ReturnRecommendationEvaluation() {
    }

    /** 라벨 한 건. {@code note}는 규칙에 들어가지 않고 LLM 층과 라벨 근거에만 쓰인다. */
    record LabelledCase(String id, ReturnRecommendationRules.Facts facts, String note,
                        ReturnRecommendationDecision label) { }

    record Prediction(LabelledCase labelled, ReturnRecommendationDecision predicted) {
        boolean falseApproval() {
            return labelled.label() == ReturnRecommendationDecision.REJECT && predicted.approves();
        }

        boolean approvalWithoutLabelledApproval() {
            return predicted.approves() && !labelled.label().approves();
        }
    }

    record Report(List<Prediction> predictions) {
        int total() { return predictions.size(); }

        long correct() { return predictions.stream().filter(p -> p.predicted() == p.labelled().label()).count(); }

        long falseApprovals() { return predictions.stream().filter(Prediction::falseApproval).count(); }

        long labelledRejects() {
            return predictions.stream().filter(p -> p.labelled().label() == ReturnRecommendationDecision.REJECT).count();
        }

        long unsafeApprovals() { return predictions.stream().filter(Prediction::approvalWithoutLabelledApproval).count(); }

        long humanReviews() {
            return predictions.stream().filter(p -> p.predicted() == ReturnRecommendationDecision.HUMAN_REVIEW).count();
        }

        List<String> disagreements() {
            return predictions.stream().filter(p -> p.predicted() != p.labelled().label())
                    .map(p -> p.labelled().id()).toList();
        }

        double macroF1() {
            double sum = 0;
            for (ReturnRecommendationDecision decision : ReturnRecommendationDecision.values()) {
                long tp = predictions.stream().filter(p -> p.predicted() == decision && p.labelled().label() == decision).count();
                long fp = predictions.stream().filter(p -> p.predicted() == decision && p.labelled().label() != decision).count();
                long fn = predictions.stream().filter(p -> p.predicted() != decision && p.labelled().label() == decision).count();
                sum += tp == 0 ? 0 : 2.0 * tp / (2.0 * tp + fp + fn);
            }
            return sum / ReturnRecommendationDecision.values().length;
        }

        String markdown(String title) {
            StringBuilder out = new StringBuilder("## ").append(title).append("\n\n");
            out.append("| 지표 | 값 |\n|---|---|\n");
            out.append("| 건수 | ").append(total()).append(" |\n");
            out.append("| 정확도 | ").append(correct()).append('/').append(total())
                    .append(String.format(" (%.3f)", (double) correct() / total())).append(" |\n");
            out.append(String.format("| 매크로 F1 | %.3f |%n", macroF1()));
            out.append("| 거짓 승인(정답 REJECT → APPROVE_*) | ").append(falseApprovals()).append('/')
                    .append(labelledRejects()).append(" |\n");
            out.append("| 승인 정답이 아닌데 승인 권고 | ").append(unsafeApprovals()).append(" |\n");
            out.append("| 사람 검토 이관 | ").append(humanReviews()).append('/').append(total()).append(" |\n");
            out.append("| 불일치 사례 | ").append(String.join(", ", disagreements())).append(" |\n\n");
            out.append("| 정답 \\ 예측 |");
            for (ReturnRecommendationDecision d : ReturnRecommendationDecision.values()) out.append(' ').append(d).append(" |");
            out.append("\n|---|---|---|---|---|\n");
            for (ReturnRecommendationDecision label : ReturnRecommendationDecision.values()) {
                Map<ReturnRecommendationDecision, Integer> row = new EnumMap<>(ReturnRecommendationDecision.class);
                predictions.stream().filter(p -> p.labelled().label() == label)
                        .forEach(p -> row.merge(p.predicted(), 1, Integer::sum));
                out.append("| ").append(label).append(" |");
                for (ReturnRecommendationDecision d : ReturnRecommendationDecision.values()) {
                    out.append(' ').append(row.getOrDefault(d, 0)).append(" |");
                }
                out.append('\n');
            }
            return out.toString();
        }
    }

    static Report evaluate(List<LabelledCase> cases, Function<LabelledCase, ReturnRecommendationDecision> recommender) {
        return new Report(cases.stream().map(c -> new Prediction(c, recommender.apply(c))).toList());
    }

    static List<LabelledCase> load() {
        try (InputStream in = Objects.requireNonNull(ReturnRecommendationEvaluation.class.getResourceAsStream(CASES))) {
            List<LabelledCase> cases = new ArrayList<>();
            for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (raw.isBlank() || raw.startsWith("#")) continue;
                cases.add(parse(raw));
            }
            return List.copyOf(cases);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static LabelledCase parse(String raw) {
        String[] cols = raw.split("\\|", -1);
        if (cols.length != 6) throw new IllegalArgumentException("case needs 6 columns: " + raw);
        String id = cols[0].strip();
        ReturnReason reason = "-".equals(cols[1].strip()) ? null : ReturnReason.valueOf(cols[1].strip());
        Instant requestedAt = DELIVERED_AT.plus(Duration.parse(cols[2].strip()));
        List<ReturnRecommendationRules.Line> lines = new ArrayList<>();
        int index = 0;
        for (String lineText : cols[3].split(";")) {
            String[] parts = lineText.strip().split("\\s+");
            String[] quantities = parts[1].split("/");
            Instant deliveredAt = parts.length < 4 ? DELIVERED_AT
                    : "-".equals(parts[3]) ? null : DELIVERED_AT.plus(Duration.parse(parts[3]));
            lines.add(new ReturnRecommendationRules.Line(
                    UUID.nameUUIDFromBytes((id + "-" + index++).getBytes(StandardCharsets.UTF_8)),
                    Integer.parseInt(quantities[0]), Integer.parseInt(quantities[1]), Integer.parseInt(quantities[2]),
                    Long.parseLong(parts[2]), deliveredAt, ReturnItemCondition.valueOf(parts[0])));
        }
        return new LabelledCase(id, new ReturnRecommendationRules.Facts(reason, requestedAt, lines),
                cols[5].strip(), ReturnRecommendationDecision.valueOf(cols[4].strip()));
    }
}
