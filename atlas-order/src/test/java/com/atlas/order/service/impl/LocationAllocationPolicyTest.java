package com.atlas.order.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.service.impl.LocationAllocationPolicy.Candidate;
import com.atlas.order.service.impl.LocationAllocationPolicy.Plan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocationAllocationPolicyTest {
    private static final UUID W1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID W2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID W3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Map<String, Integer> ORDER = Map.of("A", 1, "B", 1);

    @Test
    void prefersTheFirstSingleLocationThatCoversEveryLine() {
        List<Plan> plan = LocationAllocationPolicy.plan(ORDER, List.of(
                new Candidate(W1, 0, Map.of("A", 5L, "B", 0L)),
                new Candidate(W3, 2, Map.of("A", 5L, "B", 5L)),
                new Candidate(W2, 1, Map.of("A", 5L, "B", 5L))), true);

        assertThat(plan).containsExactly(new Plan(W2, Map.of("A", 1, "B", 1)));
    }

    @Test
    void splitsOnlyWhenAllowedAndNoSingleLocationCoversTheOrder() {
        List<Candidate> candidates = List.of(
                new Candidate(W1, 0, Map.of("A", 1L, "B", 0L)),
                new Candidate(W2, 1, Map.of("A", 0L, "B", 1L)));

        assertThat(LocationAllocationPolicy.plan(ORDER, candidates, false)).isEmpty();
        assertThat(LocationAllocationPolicy.plan(ORDER, candidates, true)).containsExactly(
                new Plan(W1, Map.of("A", 1)), new Plan(W2, Map.of("B", 1)));
        assertThat(LocationAllocationPolicy.plan(Map.of("A", 2), candidates, true)).isEmpty();
    }
}
