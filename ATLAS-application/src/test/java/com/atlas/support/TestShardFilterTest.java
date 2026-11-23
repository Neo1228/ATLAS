package com.atlas.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestShardFilterTest {
    private static final Map<String, Double> WEIGHTS = weights();

    @Test
    void everyClassRunsInExactlyOneShard() {
        List<String> classes = List.of("A", "B", "C", "D", "E",
                "F", "G", "H", "NewlyAddedWithoutWeight",
                "Another$Nested");
        for (int shards = 1; shards <= 6; shards++) {
            for (String className : classes) {
                int ran = 0;
                for (int shard = 1; shard <= shards; shard++) {
                    if (new TestShardFilter(shard + "/" + shards, WEIGHTS).includes(className)) ran++;
                }
                assertThat(ran).as(className + " with " + shards + " shards").isEqualTo(1);
            }
        }
    }

    @Test
    void nestedClassFollowsItsTopLevelClass() {
        TestShardFilter filter = new TestShardFilter("1/3", WEIGHTS);
        assertThat(filter.shardOf("A$Inner")).isEqualTo(filter.shardOf("A"));
    }

    @Test
    void largestClassesAreSpreadAcrossShards() {
        TestShardFilter filter = new TestShardFilter("1/4", WEIGHTS);
        double[] load = new double[4];
        WEIGHTS.forEach((name, seconds) -> load[filter.shardOf(name)] += seconds);
        double total = WEIGHTS.values().stream().mapToDouble(Double::doubleValue).sum();
        double largest = WEIGHTS.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        for (double shardLoad : load) {
            assertThat(shardLoad).isLessThanOrEqualTo(total / 4 + largest);
        }
    }

    @Test
    void assignmentIsDeterministic() {
        TestShardFilter first = new TestShardFilter("2/4", WEIGHTS);
        TestShardFilter second = new TestShardFilter("2/4", new HashMap<>(WEIGHTS));
        for (String className : WEIGHTS.keySet()) {
            assertThat(first.shardOf(className)).isEqualTo(second.shardOf(className));
        }
    }

    @Test
    void blankSpecRunsEverything() {
        TestShardFilter filter = new TestShardFilter("", WEIGHTS);
        assertThat(WEIGHTS.keySet()).allMatch(filter::includes);
        assertThat(filter.includes("NewlyAddedWithoutWeight")).isTrue();
    }

    @Test
    void malformedOrOutOfRangeSpecFailsInsteadOfSilentlySkippingTests() {
        assertThatThrownBy(() -> new TestShardFilter("abc", WEIGHTS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestShardFilter("0/4", WEIGHTS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestShardFilter("5/4", WEIGHTS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestShardFilter("1", WEIGHTS)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Double> weights() {
        Map<String, Double> weights = new HashMap<>();
        double[] seconds = {36, 34, 33, 19, 12, 10, 9, 5};
        String[] names = {"A", "B", "C", "D", "E", "F", "G", "H"};
        for (int i = 0; i < names.length; i++) {
            weights.put(names[i], seconds[i]);
        }
        return weights;
    }
}
