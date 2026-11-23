package com.atlas.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * CI에서 테스트 클래스를 여러 runner에 나누어 실행하기 위한 JUnit Platform 필터.
 *
 * <p>{@code atlas.test.shard=<번호>/<전체 수>}(번호는 1부터)일 때만 동작하고, 비어 있으면 모든 테스트를 실행한다.
 * 클래스 단위로 나누므로 한 클래스의 컨테이너와 메서드는 항상 같은 shard에 들어간다.
 *
 * <p>배정은 {@code ci/test-shard-weights.properties}에 기록된 실측 시간(초)을 가중치로 쓰는 LPT 방식이다.
 * 큰 클래스부터 가장 가벼운 shard에 넣는다. 가중치가 없는 새 클래스는 이름 해시로 배정하므로 어떤 클래스도
 * 빠지거나 두 번 실행되지 않는다. 가중치 파일은 {@code scripts/ci/update-test-shard-weights.py}로 갱신한다.
 */
public class TestShardFilter implements PostDiscoveryFilter {
    static final String SHARD_PROPERTY = "atlas.test.shard";
    static final String WEIGHTS_RESOURCE = "/ci/test-shard-weights.properties";

    private final int index;
    private final int count;
    private final Map<String, Integer> assignment;

    public TestShardFilter() {
        this(System.getProperty(SHARD_PROPERTY, ""), loadWeights());
    }

    TestShardFilter(String spec, Map<String, Double> weights) {
        if (spec == null || spec.isBlank()) {
            this.index = 0;
            this.count = 1;
        } else {
            String[] parts = spec.trim().split("/");
            try {
                int shard = Integer.parseInt(parts[0].trim());
                this.count = Integer.parseInt(parts[1].trim());
                this.index = shard - 1;
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(SHARD_PROPERTY + " must look like 1/4 but was '" + spec + "'");
            }
            if (count < 1 || index < 0 || index >= count) {
                throw new IllegalArgumentException(SHARD_PROPERTY + " is out of range: '" + spec + "'");
            }
        }
        this.assignment = assign(weights, count);
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        String className = classNameOf(descriptor);
        if (className == null || includes(className)) {
            return FilterResult.included("shard " + (index + 1) + "/" + count);
        }
        return FilterResult.excluded("other shard");
    }

    boolean includes(String className) {
        return shardOf(className) == index;
    }

    int shardOf(String className) {
        String topLevel = topLevel(className);
        Integer assigned = assignment.get(topLevel);
        return assigned != null ? assigned : Math.floorMod(topLevel.hashCode(), count);
    }

    private static String classNameOf(TestDescriptor descriptor) {
        return descriptor.getSource()
                .map(source -> {
                    if (source instanceof ClassSource classSource) return classSource.getClassName();
                    if (source instanceof MethodSource methodSource) return methodSource.getClassName();
                    return null;
                })
                .orElse(null);
    }

    private static String topLevel(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static Map<String, Integer> assign(Map<String, Double> weights, int count) {
        Map<String, Integer> assigned = new HashMap<>();
        double[] load = new double[count];
        weights.entrySet().stream()
                .sorted((a, b) -> {
                    int byWeight = Double.compare(b.getValue(), a.getValue());
                    return byWeight != 0 ? byWeight : a.getKey().compareTo(b.getKey());
                })
                .forEach(entry -> {
                    int lightest = 0;
                    for (int i = 1; i < count; i++) {
                        if (load[i] < load[lightest]) lightest = i;
                    }
                    load[lightest] += entry.getValue();
                    assigned.put(entry.getKey(), lightest);
                });
        return assigned;
    }

    private static Map<String, Double> loadWeights() {
        String spec = System.getProperty(SHARD_PROPERTY, "");
        if (spec == null || spec.isBlank()) return Map.of();
        try (InputStream stream = TestShardFilter.class.getResourceAsStream(WEIGHTS_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + WEIGHTS_RESOURCE + " while sharding is enabled");
            }
            Properties properties = new Properties();
            properties.load(stream);
            Map<String, Double> weights = new HashMap<>();
            for (String name : properties.stringPropertyNames()) {
                weights.put(name, Double.parseDouble(properties.getProperty(name).trim()));
            }
            return weights;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
