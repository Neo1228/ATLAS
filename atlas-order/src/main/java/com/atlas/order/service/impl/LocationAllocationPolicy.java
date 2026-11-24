package com.atlas.order.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * SellerOrder 한 건을 처리할 location을 고른다.
 *
 * <p>규칙: 우선순위(낮은 값 우선)·location ID 순으로 모든 line을 혼자 처리할 수 있는 첫 location을 고른다.
 * 그런 location이 없고 분할이 허용되면 같은 순서로 SKU 수량을 여러 location에 나눈다. 분할 출고는 배송 건수와
 * 부분 실패 범위를 늘리므로 단일 location으로 처리할 수 없을 때만 사용한다. 입력 재고는 원격 location의
 * 조회 시점 값이며, 최종 판단은 원격 예약이 한다.
 */
public final class LocationAllocationPolicy {
    private LocationAllocationPolicy() {
    }

    public static List<Plan> plan(Map<String, Integer> required, List<Candidate> candidates, boolean allowSplit) {
        if (required == null || required.isEmpty()) return List.of();
        List<Candidate> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::priority).thenComparing(Candidate::locationId))
                .toList();
        for (Candidate candidate : ordered) {
            boolean coversAll = required.entrySet().stream().allMatch(line ->
                    candidate.available().getOrDefault(line.getKey(), 0L) >= line.getValue());
            if (coversAll) return List.of(new Plan(candidate.locationId(), new TreeMap<>(required)));
        }
        if (!allowSplit) return List.of();
        Map<String, Integer> remaining = new TreeMap<>(required);
        Map<UUID, Map<String, Integer>> split = new LinkedHashMap<>();
        for (Candidate candidate : ordered) {
            for (Map.Entry<String, Integer> line : remaining.entrySet()) {
                long available = candidate.available().getOrDefault(line.getKey(), 0L);
                int take = (int) Math.min(line.getValue(), available);
                if (take < 1) continue;
                split.computeIfAbsent(candidate.locationId(), ignored -> new TreeMap<>()).put(line.getKey(), take);
                line.setValue(line.getValue() - take);
            }
        }
        if (remaining.values().stream().anyMatch(quantity -> quantity > 0)) return List.of();
        List<Plan> plans = new ArrayList<>(split.size());
        split.forEach((locationId, lines) -> plans.add(new Plan(locationId, lines)));
        return List.copyOf(plans);
    }

    /** 원격 location 한 곳의 우선순위와 SKU별 판매 가능 수량. */
    public record Candidate(UUID locationId, int priority, Map<String, Long> available) {
    }

    /** location 한 곳에서 예약할 SKU별 수량. */
    public record Plan(UUID locationId, Map<String, Integer> lines) {
    }
}
