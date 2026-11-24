package com.atlas.order.service.impl;

import com.atlas.order.dto.request.LocationReservationItemRequest;
import com.atlas.order.exception.LocationClientException;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.LocationReservationWorkflowState;
import com.atlas.order.persistence.projection.LocationRestock;
import com.atlas.order.persistence.projection.ReservationWorkflowItemRecord;
import com.atlas.order.persistence.projection.ReservationWorkflowLocationSnapshot;
import com.atlas.order.persistence.projection.ReservationWorkflowSnapshot;
import com.atlas.order.repository.LocationReservationRepository;
import com.atlas.order.repository.LocationRestockRepository;
import com.atlas.order.service.LocationClient;
import com.atlas.order.service.LocationRestockService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 원격 재고로 판매한 주문의 환불 재입고를 처리한다.
 *
 * <p>{@link #stage}는 환불 확정 transaction 안에서 재입고 의도만 저장한다. 원격 호출은 commit 뒤
 * {@link #dispatch}와 recovery worker의 {@link #recoverDue}가 같은 restock key로 수행하며, 원격 restock은
 * key 단위로 멱등이므로 응답 유실·재시작 뒤 재시도해도 한 번만 반영된다.
 */
@Service
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.enabled", havingValue = "true")
public class LocationRestockServiceImpl implements LocationRestockService {
    private final LocationReservationRepository workflows;
    private final LocationRestockRepository restocks;
    private final LocationClient inventory;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;

    public LocationRestockServiceImpl(LocationReservationRepository workflows,
            LocationRestockRepository restocks, LocationClient inventory, Clock clock,
            @Value("${atlas.order-inventory-reservation.recovery.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${atlas.order-inventory-reservation.recovery.base-backoff:PT1S}") Duration baseBackoff,
            @Value("${atlas.order-inventory-reservation.recovery.max-backoff:PT1M}") Duration maxBackoff,
            @Value("${atlas.order-inventory-reservation.recovery.max-attempts:8}") int maxAttempts) {
        this.workflows = Objects.requireNonNull(workflows);
        this.restocks = Objects.requireNonNull(restocks);
        this.inventory = Objects.requireNonNull(inventory);
        this.clock = Objects.requireNonNull(clock);
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || baseBackoff == null || baseBackoff.isNegative() || baseBackoff.isZero()
                || maxBackoff == null || maxBackoff.compareTo(baseBackoff) < 0 || maxAttempts < 1) {
            throw new IllegalArgumentException("Remote inventory restock recovery policy is invalid");
        }
        this.leaseDuration = leaseDuration;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.maxAttempts = maxAttempts;
    }

    public static String restockKey(UUID refundId) {
        return "refund-restock:" + refundId;
    }

    @Override
    public void stage(UUID refundId, UUID orderId, UUID workflowId, UUID sellerId, Map<String, Integer> quantitiesBySku) {
        ReservationWorkflowSnapshot workflow = workflows.findByWorkflowId(workflowId);
        if (workflow == null || !workflow.orderId().equals(orderId)
                || workflow.state() != LocationReservationWorkflowState.CLAIMED) {
            throw new OrderException(OrderException.Code.CONFLICT, "Order inventory is not claimed for restock");
        }
        Map<String, Integer> remaining = new LinkedHashMap<>(quantitiesBySku);
        Instant now = clock.instant();
        for (ReservationWorkflowLocationSnapshot location : workflow.locations()) {
            List<ReservationWorkflowItemRecord> items = new ArrayList<>();
            for (ReservationWorkflowItemRecord sold : location.items()) {
                if (!sold.sellerId().equals(sellerId)) continue;
                int wanted = remaining.getOrDefault(sold.skuId(), 0);
                int take = Math.min(wanted, sold.quantity());
                if (take < 1) continue;
                items.add(new ReservationWorkflowItemRecord(sellerId, sold.skuId(), take));
                remaining.put(sold.skuId(), wanted - take);
            }
            if (!items.isEmpty()) {
                restocks.insert(restockKey(refundId), location.fulfillmentLocationId(), refundId, orderId,
                        workflowId, location.reservationId(), items, now);
            }
        }
        if (remaining.values().stream().anyMatch(quantity -> quantity > 0)) {
            throw new OrderException(OrderException.Code.CONFLICT, "Refund quantity exceeds the sold remote stock");
        }
    }

    @Override
    public void stageAtLocations(UUID refundId, UUID orderId, UUID workflowId, UUID sellerId,
            Map<UUID, Map<String, Integer>> quantitiesByLocation) {
        ReservationWorkflowSnapshot workflow = workflows.findByWorkflowId(workflowId);
        if (workflow == null || !workflow.orderId().equals(orderId)
                || workflow.state() != LocationReservationWorkflowState.CLAIMED) {
            throw new OrderException(OrderException.Code.CONFLICT, "Order inventory is not claimed for restock");
        }
        Map<UUID, Map<String, Integer>> remaining = new LinkedHashMap<>();
        quantitiesByLocation.forEach((locationId, quantities) ->
                remaining.put(locationId, new LinkedHashMap<>(quantities)));
        if (remaining.containsKey(null)) {
            if (workflow.locations().size() != 1) {
                throw new OrderException(OrderException.Code.CONFLICT, "Return shipment location is unavailable");
            }
            UUID onlyLocationId = workflow.locations().getFirst().fulfillmentLocationId();
            Map<String, Integer> unlocated = remaining.remove(null);
            remaining.merge(onlyLocationId, unlocated, (current, added) -> {
                added.forEach((sku, quantity) -> current.merge(sku, quantity, Math::addExact));
                return current;
            });
        }
        Instant now = clock.instant();
        for (ReservationWorkflowLocationSnapshot location : workflow.locations()) {
            Map<String, Integer> requested = remaining.remove(location.fulfillmentLocationId());
            if (requested == null) continue;
            List<ReservationWorkflowItemRecord> items = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : requested.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                        || entry.getValue() < 1) {
                    throw new OrderException(OrderException.Code.CONFLICT, "Invalid remote restock allocation");
                }
                int claimed = location.items().stream()
                        .filter(item -> item.sellerId().equals(sellerId) && item.skuId().equals(entry.getKey()))
                        .mapToInt(ReservationWorkflowItemRecord::quantity)
                        .sum();
                if (entry.getValue() > claimed) {
                    throw new OrderException(OrderException.Code.CONFLICT,
                            "Refund quantity exceeds shipment location allocation");
                }
                items.add(new ReservationWorkflowItemRecord(sellerId, entry.getKey(), entry.getValue()));
            }
            restocks.insert(restockKey(refundId), location.fulfillmentLocationId(), refundId, orderId,
                    workflowId, location.reservationId(), items, now);
        }
        if (!remaining.isEmpty()) {
            throw new OrderException(OrderException.Code.CONFLICT,
                    "Refund shipment location is not in reservation workflow");
        }
    }

    @Override
    public void dispatch(UUID refundId) {
        Instant now = clock.instant();
        for (LocationRestock restock : restocks.claimDue(now, now.plus(leaseDuration), 100, restockKey(refundId))) {
            process(restock);
        }
    }

    @Override
    public double oldestDueAgeSeconds() {
        return restocks.oldestDueAgeSeconds(clock.instant());
    }

    @Override
    public int recoverDue(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid restock recovery batch size");
        Instant now = clock.instant();
        List<LocationRestock> due = restocks.claimDue(now, now.plus(leaseDuration), batchSize, null);
        due.forEach(this::process);
        return due.size();
    }

    private void process(LocationRestock restock) {
        try {
            inventory.restock(restock.fulfillmentLocationId(), restock.reservationId(), restock.restockKey(),
                    restock.items().stream().map(item -> new LocationReservationItemRequest(
                            item.sellerId(), item.skuId(), item.quantity())).toList());
            restocks.finish(restock.restockKey(), restock.fulfillmentLocationId(), restock.attemptCount(), "COMPLETED", null,
                    clock.instant(), clock.instant());
        } catch (LocationClientException exception) {
            Instant now = clock.instant();
            boolean retryable = exception.failure() == LocationClientException.Failure.RETRYABLE_UNKNOWN;
            if (!retryable || restock.attemptCount() >= maxAttempts) {
                // 자동 재시도로 해결되지 않는 재입고는 수량을 추정해 보정하지 않고 검토 대상으로 남긴다.
                restocks.finish(restock.restockKey(), restock.fulfillmentLocationId(), restock.attemptCount(), "NEEDS_REVIEW",
                        exception.providerCode(), now, now);
                return;
            }
            restocks.finish(restock.restockKey(), restock.fulfillmentLocationId(), restock.attemptCount(), "PENDING",
                    exception.providerCode(), now.plus(backoff(restock.attemptCount())), now);
        }
    }

    private Duration backoff(int attempt) {
        Duration delay = baseBackoff.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 10));
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
