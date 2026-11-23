package com.atlas.application.service.impl;

import com.atlas.order.service.LocationClient;
import com.atlas.order.service.StockAdmissionService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis 없이 거절 비용을 줄이는 대안이다. workflow 행을 만들기 전에 location의 판매 가능 수량을 읽고, 요청 수량보다
 * 적으면 곧바로 거절한다. order DB에는 아무것도 쓰지 않는다.
 *
 * <p>확인된 품절(수량 부족)만 {@code sold-out-ttl} 동안 프로세스 안에 기억한다(음성 캐시). 품절이 알려진 SKU의 요청은
 * location 호출 없이 거절되므로, 거절이 몰릴 때 location의 읽기 부하가 생기지 않는다. 판매 가능이었던 결과는 캐시하지
 * 않아 재고가 되돌아온 경우 TTL 안에 늦게 보일 뿐이다(그 사이의 요청은 오거절이 될 수 있다).
 *
 * <p>차감하지 않는 읽기라 동시에 들어온 요청은 같은 수량을 보고 통과할 수 있다. 그 요청들은 뒤의 정본 경로(location의
 * 조건부 UPDATE)에서 거절되므로 초과 예약은 생기지 않는다. 인스턴스가 여러 개면 TTL마다 인스턴스당 한 번씩 location을 읽는다.
 * Redis gate({@link RedisStockAdmissionService})와 동시에 켜지 않는다.
 */
@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.admission-gate.precheck.enabled", havingValue = "true")
public class PrecheckStockAdmissionService implements StockAdmissionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrecheckStockAdmissionService.class);
    private static final int MAX_CACHED_KEYS = 100_000;

    private final LocationClient inventory;
    private final long soldOutTtlNanos;
    private final ConcurrentHashMap<String, Long> soldOutUntilNanos = new ConcurrentHashMap<>();

    public PrecheckStockAdmissionService(LocationClient inventory,
            @Value("${atlas.order-inventory-reservation.admission-gate.precheck.sold-out-ttl:PT1S}")
            Duration soldOutTtl) {
        if (soldOutTtl.isNegative()) {
            throw new IllegalArgumentException("Sold-out TTL must not be negative");
        }
        this.inventory = inventory;
        this.soldOutTtlNanos = soldOutTtl.toNanos();
    }

    @Override
    public boolean admit(String admissionKey, List<Claim> claims) {
        try {
            long now = System.nanoTime();
            for (Claim claim : claims) {
                String key = claim.fulfillmentLocationId() + ":" + claim.sellerId() + ":" + claim.skuId();
                Long until = soldOutUntilNanos.get(key);
                if (until != null && now - until < 0) {
                    return false;
                }
                long available = inventory.availableStock(
                        claim.fulfillmentLocationId(), claim.sellerId(), claim.skuId());
                if (available < claim.quantity()) {
                    remember(key, now);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            // 확인하지 못했으면 거절하지 않는다. 정본 검증이 뒤에 남아 있다.
            LOGGER.warn("Availability precheck failed; falling through: {}", exception.getClass().getSimpleName());
            return true;
        }
    }

    private void remember(String key, long now) {
        if (soldOutTtlNanos == 0) {
            return;
        }
        if (soldOutUntilNanos.size() >= MAX_CACHED_KEYS) {
            soldOutUntilNanos.values().removeIf(until -> now - until >= 0);
            if (soldOutUntilNanos.size() >= MAX_CACHED_KEYS) {
                return;
            }
        }
        soldOutUntilNanos.put(key, now + soldOutTtlNanos);
    }
}
