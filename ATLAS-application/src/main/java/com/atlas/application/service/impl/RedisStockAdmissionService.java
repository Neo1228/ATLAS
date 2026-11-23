package com.atlas.application.service.impl;

import com.atlas.order.dto.request.StockClaimRequest;
import com.atlas.order.service.LocationClient;
import com.atlas.order.service.StockAdmissionService;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SKU별 "허용 가능 수량"을 Redis에 두고 Lua 한 번으로 확인·차감한다. 여러 SKU는 모두 통과해야 차감된다.
 *
 * <p>정본은 아니다. 수량은 location의 실제 판매 가능 수량으로 채우고 {@code stock-ttl}마다 다시 채운다.
 * 따라서 Redis가 비거나 틀려도 최악은 일시적인 오거절 또는 통과이며, 초과 예약은 location의 조건부 UPDATE가 막는다.
 * Redis 오류는 거절이 아니라 통과로 처리한다(fail-open). 이때는 기존 PostgreSQL 경로의 처리량으로 돌아간다.
 *
 * <p>단일 Redis 노드를 전제로 한다. 여러 key를 한 스크립트에서 다루므로 cluster에서는 hash tag 설계가 필요하다.
 */
@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.admission-gate.enabled", havingValue = "true")
public class RedisStockAdmissionService implements StockAdmissionService, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisStockAdmissionService.class);
    private static final long UNSEEDED = -1;
    // decision 값: 0 거절, 1 승인(예약 전), 2 반환됨, 3 예약 확정. held 해시는 승인 때 잡은 SKU별 수량이다.
    private static final String ADMIT_SCRIPT = """
            local decided = redis.call('GET', KEYS[1])
            if decided then
              if decided == '0' then return 0 end
              return 1
            end
            for i = 2, #KEYS do
              local available = redis.call('GET', KEYS[i])
              if not available then return -1 end
              if tonumber(available) < tonumber(ARGV[i]) then
                redis.call('SET', KEYS[1], '0', 'PX', ARGV[1])
                return 0
              end
            end
            local held = KEYS[1] .. ':held'
            for i = 2, #KEYS do
              redis.call('DECRBY', KEYS[i], ARGV[i])
              redis.call('INCRBY', 'atlas:adm:pend:' .. KEYS[i], ARGV[i])
              redis.call('PEXPIRE', 'atlas:adm:pend:' .. KEYS[i], ARGV[1])
              redis.call('HSET', held, KEYS[i], ARGV[i])
            end
            redis.call('PEXPIRE', held, ARGV[1])
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            return 1
            """;
    private static final String CONFIRM_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= '1' then return 0 end
            local held = KEYS[1] .. ':held'
            local flat = redis.call('HGETALL', held)
            for i = 1, #flat, 2 do
              local pend = 'atlas:adm:pend:' .. flat[i]
              if redis.call('DECRBY', pend, flat[i + 1]) < 0 then redis.call('SET', pend, '0') end
            end
            redis.call('DEL', held)
            redis.call('SET', KEYS[1], '3', 'PX', ARGV[1])
            return 1
            """;
    private static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= '1' then return 0 end
            local held = KEYS[1] .. ':held'
            local flat = redis.call('HGETALL', held)
            for i = 1, #flat, 2 do
              if redis.call('EXISTS', flat[i]) == 1 then redis.call('INCRBY', flat[i], flat[i + 1]) end
              local pend = 'atlas:adm:pend:' .. flat[i]
              if redis.call('DECRBY', pend, flat[i + 1]) < 0 then redis.call('SET', pend, '0') end
            end
            redis.call('DEL', held)
            redis.call('SET', KEYS[1], '2', 'PX', ARGV[1])
            return 1
            """;

    private final LocationClient inventory;
    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;
    private final long decisionTtlMillis;
    private final long stockTtlMillis;
    private final Map<String, Object> seedLocks = new ConcurrentHashMap<>();
    private final Counter admitted;
    private final Counter rejected;
    private final Counter failedOpen;
    private final long breakerCooldownNanos;
    private volatile long breakerOpenUntilNanos;

    public RedisStockAdmissionService(LocationClient inventory, MeterRegistry meters,
            @Value("${atlas.order-inventory-reservation.admission-gate.redis-uri}") String redisUri,
            @Value("${atlas.order-inventory-reservation.admission-gate.command-timeout:PT0.2S}") Duration commandTimeout,
            @Value("${atlas.order-inventory-reservation.admission-gate.stock-ttl:PT5S}") Duration stockTtl,
            @Value("${atlas.order-inventory-reservation.admission-gate.decision-ttl:PT10M}") Duration decisionTtl,
            @Value("${atlas.order-inventory-reservation.admission-gate.breaker-cooldown:PT2S}") Duration breakerCooldown) {
        if (stockTtl.isZero() || stockTtl.isNegative() || decisionTtl.isZero() || decisionTtl.isNegative()) {
            throw new IllegalArgumentException("Admission gate TTLs must be positive");
        }
        this.inventory = inventory;
        this.stockTtlMillis = stockTtl.toMillis();
        this.decisionTtlMillis = decisionTtl.toMillis();
        this.breakerCooldownNanos = breakerCooldown.toNanos();
        RedisURI uri = RedisURI.create(redisUri);
        uri.setTimeout(commandTimeout);
        this.client = RedisClient.create(uri);
        this.admitted = Counter.builder("atlas.order.admission.gate").tag("result", "admitted").register(meters);
        this.rejected = Counter.builder("atlas.order.admission.gate").tag("result", "rejected").register(meters);
        this.failedOpen = Counter.builder("atlas.order.admission.gate").tag("result", "fail_open").register(meters);
    }

    @Override
    public boolean admit(String admissionKey, List<StockClaimRequest> claims) {
        if (System.nanoTime() - breakerOpenUntilNanos < 0) {
            // Redis가 방금 실패했다. 요청마다 timeout을 기다리지 않고 곧바로 정본 경로로 보낸다.
            failedOpen.increment();
            return true;
        }
        try {
            Map<String, StockClaimRequest> merged = merge(claims);
            long first = evaluate(admissionKey, merged);
            if (first == UNSEEDED) {
                seedMissing(merged);
                first = evaluate(admissionKey, merged);
            }
            if (first == UNSEEDED) {
                failedOpen.increment();
                return true;
            }
            if (first == 1) {
                admitted.increment();
                return true;
            }
            rejected.increment();
            return false;
        } catch (RuntimeException exception) {
            failedOpen.increment();
            breakerOpenUntilNanos = System.nanoTime() + breakerCooldownNanos;
            LOGGER.warn("Admission gate unavailable; bypassing it for {} ms: {}",
                    breakerCooldownNanos / 1_000_000, exception.getClass().getSimpleName());
            return true;
        }
    }

    @Override
    public void confirm(String admissionKey) {
        settle(CONFIRM_SCRIPT, admissionKey);
    }

    @Override
    public void release(String admissionKey) {
        settle(RELEASE_SCRIPT, admissionKey);
    }

    /** 정리는 최선 노력이다. Redis가 닫혀 있거나 실패하면 건너뛰고 stock TTL 재시드가 치유한다. */
    private void settle(String script, String admissionKey) {
        if (System.nanoTime() - breakerOpenUntilNanos < 0) {
            return;
        }
        try {
            commands().eval(script, ScriptOutputType.INTEGER, new String[] {"atlas:adm:decision:" + admissionKey},
                    Long.toString(decisionTtlMillis));
        } catch (RuntimeException exception) {
            breakerOpenUntilNanos = System.nanoTime() + breakerCooldownNanos;
            LOGGER.warn("Admission gate settle failed: {}", exception.getClass().getSimpleName());
        }
    }

    private long evaluate(String admissionKey, Map<String, StockClaimRequest> merged) {
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        keys.add("atlas:adm:decision:" + admissionKey);
        args.add(Long.toString(decisionTtlMillis));
        merged.forEach((key, claim) -> {
            keys.add(key);
            args.add(Integer.toString(claim.quantity()));
        });
        RedisCommands<String, String> commands = commands();
        Long result = commands.eval(ADMIT_SCRIPT, ScriptOutputType.INTEGER,
                keys.toArray(String[]::new), args.toArray(String[]::new));
        return result == null ? UNSEEDED : result;
    }

    /** 비어 있는 SKU key만 location의 실제 판매 가능 수량으로 채운다. 이미 있으면 덮어쓰지 않는다. */
    private void seedMissing(Map<String, StockClaimRequest> merged) {
        RedisCommands<String, String> commands = commands();
        for (Map.Entry<String, StockClaimRequest> entry : merged.entrySet()) {
            Object lock = seedLocks.computeIfAbsent(entry.getKey(), ignored -> new Object());
            synchronized (lock) {
                if (commands.exists(entry.getKey()) > 0) {
                    continue;
                }
                StockClaimRequest claim = entry.getValue();
                long available = inventory.availableStock(
                        claim.fulfillmentLocationId(), claim.sellerId(), claim.skuId());
                // 승인됐지만 아직 location에 예약되지 않은 수량은 availableStock에 반영돼 있지 않다. 빼 줘야 과다 승인이 없다.
                String pending = commands.get("atlas:adm:pend:" + entry.getKey());
                long seeded = Math.max(0, available - (pending == null ? 0 : Long.parseLong(pending)));
                commands.set(entry.getKey(), Long.toString(seeded), SetArgs.Builder.nx().px(stockTtlMillis));
            }
        }
    }

    /** 첫 사용 때 연결한다. 부팅 시점의 Redis 장애가 애플리케이션 기동을 막지 않게 하기 위해서다. */
    private RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null) {
            synchronized (this) {
                if (connection == null) {
                    connection = client.connect();
                }
                current = connection;
            }
        }
        return current.sync();
    }

    private static Map<String, StockClaimRequest> merge(List<StockClaimRequest> claims) {
        Map<String, StockClaimRequest> merged = new TreeMap<>();
        for (StockClaimRequest claim : claims) {
            String key = "atlas:adm:stock:" + claim.fulfillmentLocationId() + ":" + claim.sellerId()
                    + ":" + claim.skuId();
            merged.merge(key, claim, (left, right) -> new StockClaimRequest(left.fulfillmentLocationId(), left.sellerId(),
                    left.skuId(), Math.addExact(left.quantity(), right.quantity())));
        }
        return merged;
    }

    @Override
    public void destroy() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
        client.shutdown();
    }
}
