package com.atlas.order.service;

import com.atlas.order.dto.request.OrderInventoryReservationRequest;
import com.atlas.order.dto.request.OrderInventoryReservationRequest.Item;
import com.atlas.order.dto.request.OrderInventoryReservationRequest.LocationAllocation;
import com.atlas.order.dto.response.OrderInventoryReservationResponse;
import com.atlas.order.dto.response.OrderInventoryReservationResponse.LocationResult;
import com.atlas.order.exception.InventoryLocationClientException;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.InventoryLocationReservationState;
import com.atlas.order.persistence.state.OrderInventoryReservationWorkflowState;
import com.atlas.order.repository.OrderInventoryReservationRepository;
import com.atlas.order.repository.OrderInventoryReservationRepository.ItemRecord;
import com.atlas.order.repository.OrderInventoryReservationRepository.LocationPlan;
import com.atlas.order.repository.OrderInventoryReservationRepository.LocationSnapshot;
import com.atlas.order.repository.OrderInventoryReservationRepository.WorkLease;
import com.atlas.order.repository.OrderInventoryReservationRepository.WorkflowSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class OrderInventoryReservationCoordinator {
    private static final Pattern SKU_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,96}");

    private final OrderInventoryReservationRepository repository;
    private final InventoryLocationReservationClient inventory;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration leaseRenewalThreshold;
    private final Duration baseRetryDelay;
    private final Duration maxRetryDelay;
    private final Duration minimumReservationRemaining;
    private final int maxAttempts;

    public OrderInventoryReservationCoordinator(OrderInventoryReservationRepository repository,
            InventoryLocationReservationClient inventory, Clock clock,
            PlatformTransactionManager transactionManager, Duration leaseDuration,
            Duration baseRetryDelay, Duration maxRetryDelay,
            Duration minimumReservationRemaining, int maxAttempts) {
        this.repository = repository;
        this.inventory = inventory;
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.leaseRenewalThreshold = leaseDuration.dividedBy(2);
        this.baseRetryDelay = baseRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.minimumReservationRemaining = minimumReservationRemaining;
        this.maxAttempts = maxAttempts;
        if (leaseDuration.isNegative() || leaseDuration.isZero()
                || baseRetryDelay.isNegative() || baseRetryDelay.isZero()
                || maxRetryDelay.compareTo(baseRetryDelay) < 0
                || minimumReservationRemaining.isNegative() || minimumReservationRemaining.isZero()
                || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid inventory reservation recovery policy");
        }
    }

    /**
     * 원격 호출 없이 workflow intent만 저장한다. 호출자는 자신의 transaction 안에서 호출해
     * intent를 소유 주문과 함께 원자적으로 commit할 수 있다.
     */
    public OrderInventoryReservationResponse stage(String idempotencyKey,
            OrderInventoryReservationRequest request) {
        UUID candidateId = UUID.randomUUID();
        WorkflowSnapshot workflow = persistIntent(candidateId, idempotencyKey, request);
        return response(workflow, !workflow.workflowId().equals(candidateId));
    }

    public OrderInventoryReservationResponse submit(String idempotencyKey,
            OrderInventoryReservationRequest request) {
        rejectIfAdmissionExhausted(idempotencyKey, request);
        UUID candidateId = UUID.randomUUID();
        SubmissionAttempt attempt = transactions.execute(status -> {
            WorkflowSnapshot workflow = persistIntent(candidateId, idempotencyKey, request);
            boolean replayed = !workflow.workflowId().equals(candidateId);
            if (terminal(workflow.state())) return new SubmissionAttempt(workflow, null, replayed);
            Instant now = clock.instant();
            Instant leaseUntil = now.plus(leaseDuration);
            UUID leaseToken = repository.claimWorkflow(workflow.workflowId(), now, leaseUntil);
            WorkLease lease = leaseToken == null ? null
                    : new WorkLease(workflow.workflowId(), leaseToken, leaseUntil);
            return new SubmissionAttempt(workflow, lease, replayed);
        });
        WorkflowSnapshot workflow = attempt.workflow();
        if (terminal(workflow.state())) return response(workflow, true);
        WorkLease lease = attempt.lease();
        if (lease == null) return response(repository.findByWorkflowId(workflow.workflowId()), true);
        process(lease);
        return response(repository.findByWorkflowId(workflow.workflowId()), attempt.replayed());
    }

    private WorkflowSnapshot persistIntent(UUID candidateId, String idempotencyKey,
            OrderInventoryReservationRequest request) {
        validateIdempotencyKey(idempotencyKey);
        if (request == null || request.orderId() == null) throw invalid("Order id is required");
        List<LocationPlan> plans = normalize(request.allocations());
        if (plans.isEmpty()) throw invalid("At least one fulfillment location is required");

        WorkflowSnapshot workflow = repository.createOrReplay(
                candidateId, request.orderId(), idempotencyKey, plans, clock.instant());
        if (workflow == null) throw new IllegalStateException("Inventory reservation workflow disappeared");
        if (!workflow.orderId().equals(request.orderId()) || !samePlans(workflow, plans)) {
            throw conflict("Idempotency key was used for different inventory reservation facts");
        }
        return workflow;
    }

    private ReservationAdmissionGate admissionGate;

    /** 선택 구성요소다. 없으면 모든 요청이 기존 경로를 그대로 탄다. */
    @Autowired(required = false)
    public void setAdmissionGate(ReservationAdmissionGate admissionGate) {
        this.admissionGate = admissionGate;
    }

    private void rejectIfAdmissionExhausted(String idempotencyKey, OrderInventoryReservationRequest request) {
        if (admissionGate == null || idempotencyKey == null || request == null || request.allocations() == null) {
            return;
        }
        List<ReservationAdmissionGate.Claim> claims = request.allocations().stream()
                .flatMap(allocation -> allocation.items().stream().map(item -> new ReservationAdmissionGate.Claim(
                        allocation.fulfillmentLocationId(), item.sellerId(), item.skuId(), item.quantity())))
                .toList();
        if (!claims.isEmpty() && !admissionGate.tryAdmit(idempotencyKey, claims)) {
            // 확실히 소진된 SKU다. workflow 행도 원격 호출도 만들지 않고 즉시 재고 부족으로 답한다.
            throw conflict("Inventory appears exhausted at the admission gate; the reservation was not attempted");
        }
    }

    public OrderInventoryReservationResponse find(UUID workflowId) {
        WorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        return response(workflow, true);
    }

    public OrderInventoryReservationResponse retry(UUID workflowId) {
        WorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        if (terminal(workflow.state())) return response(workflow, true);
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(leaseDuration);
        UUID leaseToken = repository.claimWorkflowForRetry(workflowId, now, leaseUntil);
        if (leaseToken != null) process(new WorkLease(workflowId, leaseToken, leaseUntil));
        return response(repository.findByWorkflowId(workflowId), true);
    }

    /** 운영자가 봐야 하는 workflow를 오래된 순으로 돌려준다. */
    public List<OrderInventoryReservationResponse> needsReview(int limit) {
        if (limit < 1 || limit > 200) throw invalid("limit must be between 1 and 200");
        return repository.findNeedsReviewIds(limit).stream().map(this::find).toList();
    }

    /**
     * NEEDS_REVIEW를 보상 재실행으로 되돌려 원격 예약을 해제한다(CLAIMED는 restore). 원격 재고를 풀기 때문에 주문을
     * 계속 진행하려는 경우에는 쓰지 않는다. 다른 상태에서 호출하면 현재 상태를 그대로 돌려준다.
     */
    public OrderInventoryReservationResponse releaseNeedsReview(UUID workflowId) {
        WorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        Instant now = clock.instant();
        if (repository.requeueNeedsReviewForCompensation(workflowId, now)) {
            UUID leaseToken = repository.claimWorkflowForRetry(workflowId, now, now.plus(leaseDuration));
            if (leaseToken != null) process(new WorkLease(workflowId, leaseToken, now.plus(leaseDuration)));
        }
        return response(repository.findByWorkflowId(workflowId), true);
    }

    public OrderInventoryReservationResponse cancel(UUID workflowId) {
        WorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        if (workflow.state().equals(OrderInventoryReservationWorkflowState.CANCELLED.name())
                || workflow.state().equals(OrderInventoryReservationWorkflowState.FAILED.name())
                || workflow.state().equals(OrderInventoryReservationWorkflowState.NEEDS_REVIEW.name())
                || cancelRequested(workflow)) {
            // 이미 요청된 취소는 recovery worker가 backoff에 따라 재시도하므로 반복 호출이 원격 호출을 늘리지 않는다.
            return response(workflow, true);
        }

        Instant now = clock.instant();
        repository.requestCancellation(workflowId, now);
        Instant leaseUntil = now.plus(leaseDuration);
        UUID leaseToken = repository.claimWorkflow(workflowId, now, leaseUntil);
        if (leaseToken != null) process(new WorkLease(workflowId, leaseToken, leaseUntil));
        return response(repository.findByWorkflowId(workflowId), true);
    }

    /**
     * 결제 승인 뒤 매입 전에 모든 location의 원격 예약을 claim한다. 원격 호출은 DB transaction 밖에서
     * 수행하고, workflow lease로 동시 claim·취소를 막는다. 반환 상태가 {@code CLAIMED}일 때만 매입해도 된다.
     * 원격 결과가 불확실하면 {@code RESERVED}로 남겨 같은 reservation ID로 다시 claim한다(원격 claim은 멱등).
     * 만료·취소로 claim이 확정 거절되면 {@code lastErrorCode}에 원인을 남기고 workflow는 {@code RESERVED}로 둔다.
     * 호출자는 승인을 void한 뒤 {@link #cancel(UUID)}로 남은 예약을 정리한다.
     */
    public ClaimOutcome claim(UUID workflowId) {
        WorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        if (workflow.state().equals(OrderInventoryReservationWorkflowState.CLAIMED.name())) return ClaimOutcome.CLAIMED;
        if (!workflow.state().equals(OrderInventoryReservationWorkflowState.RESERVED.name())) {
            return terminal(workflow.state()) || cancelRequested(workflow)
                    ? ClaimOutcome.REJECTED : ClaimOutcome.PENDING;
        }
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(leaseDuration);
        UUID leaseToken = repository.leaseForClaim(workflowId, now, leaseUntil);
        if (leaseToken == null) return ClaimOutcome.PENDING;
        WorkLease lease = new WorkLease(workflowId, leaseToken, leaseUntil);
        for (LocationSnapshot location : workflow.locations()) {
            if (location.state().equals(InventoryLocationReservationState.CLAIMED.name())) continue;
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return ClaimOutcome.PENDING;
            try {
                InventoryLocationReservationClient.RemoteReservation claimed =
                        inventory.claim(location.fulfillmentLocationId(), location.reservationId());
                if (!"CLAIMED".equals(claimed.status())) {
                    finish(workflow, lease.leaseToken(), OrderInventoryReservationWorkflowState.RESERVED,
                            "CLAIM_RESULT_UNKNOWN", clock.instant());
                    return ClaimOutcome.PENDING;
                }
                repository.updateLocation(workflowId, lease.leaseToken(), location.fulfillmentLocationId(),
                        InventoryLocationReservationState.CLAIMED, location.reservationId(),
                        location.remoteExpiresAt(), null, clock.instant());
            } catch (InventoryLocationClientException exception) {
                finish(workflow, lease.leaseToken(), OrderInventoryReservationWorkflowState.RESERVED,
                        exception.providerCode(), clock.instant());
                return exception.failure() == InventoryLocationClientException.Failure.RETRYABLE_UNKNOWN
                        ? ClaimOutcome.PENDING : ClaimOutcome.REJECTED;
            }
        }
        finish(workflow, lease.leaseToken(), OrderInventoryReservationWorkflowState.CLAIMED, null, clock.instant());
        return ClaimOutcome.CLAIMED;
    }

    public enum ClaimOutcome { CLAIMED, PENDING, REJECTED }
    private record SubmissionAttempt(WorkflowSnapshot workflow, WorkLease lease, boolean replayed) {}
    private record LocationTransition(
            UUID fulfillmentLocationId,
            InventoryLocationReservationState state,
            UUID reservationId,
            Instant remoteExpiresAt,
            String errorCode) {}

    public long needsReviewCount(boolean claimedResidual) {
        return repository.countNeedsReview(claimedResidual);
    }

    public double oldestDueAgeSeconds() {
        return repository.oldestDueAgeSeconds(clock.instant());
    }

    /**
     * poll 시작 시각까지 due인 workflow를 한 건씩 lease하고 즉시 처리한다.
     * poll 도중 새로 예약된 retry는 다음 poll에 맡겨 같은 poll의 재시도 loop를 막는다.
     */
    public int recoverDue(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw invalid("Recovery batch size must be between 1 and 1000");
        Instant dueThrough = clock.instant();
        int processed = 0;
        while (processed < batchSize) {
            Instant now = clock.instant();
            WorkLease lease = repository.claimNextDue(dueThrough, now, now.plus(leaseDuration));
            if (lease == null) break;
            process(lease);
            processed++;
        }
        return processed;
    }

    private WorkLease renewLeaseIfNeeded(WorkLease lease) {
        Instant now = clock.instant();
        if (lease.leaseUntil().isAfter(now.plus(leaseRenewalThreshold))) return lease;
        Instant leaseUntil = now.plus(leaseDuration);
        return repository.renewLease(lease.workflowId(), lease.leaseToken(), now, leaseUntil)
                ? new WorkLease(lease.workflowId(), lease.leaseToken(), leaseUntil) : null;
    }

    private void process(WorkLease lease) {
        WorkflowSnapshot workflow = repository.findByWorkflowId(lease.workflowId());
        if (workflow == null) return;
        if (workflow.state().equals(OrderInventoryReservationWorkflowState.COMPENSATING.name())
                || workflow.state().equals(OrderInventoryReservationWorkflowState.COMPENSATION_REQUIRED.name())
                || workflow.state().equals(OrderInventoryReservationWorkflowState.CANCEL_PENDING.name())) {
            compensate(workflow, lease);
            return;
        }
        if (workflow.state().equals(OrderInventoryReservationWorkflowState.RESERVED.name())
                || workflow.state().equals(OrderInventoryReservationWorkflowState.FAILED.name())
                || workflow.state().equals(OrderInventoryReservationWorkflowState.NEEDS_REVIEW.name())) {
            repository.finishWorkflow(workflow.workflowId(), lease.leaseToken(),
                    OrderInventoryReservationWorkflowState.valueOf(workflow.state()), workflow.lastErrorCode(),
                    workflow.nextAttemptAt(), clock.instant());
            return;
        }

        if ((workflow.state().equals(OrderInventoryReservationWorkflowState.PENDING.name())
                || workflow.state().equals(OrderInventoryReservationWorkflowState.RETRY_PENDING.name()))
                && workflow.locations().stream().allMatch(location ->
                location.state().equals(InventoryLocationReservationState.PENDING.name()))) {
            processPendingWorkflow(workflow, lease);
            return;
        }

        for (LocationSnapshot location : workflow.locations()) {
            if (location.state().equals(InventoryLocationReservationState.RESERVED.name())) {
                if (reservationUsable(location, clock.instant())) continue;
                repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), InventoryLocationReservationState.RELEASE_PENDING,
                        location.reservationId(), location.remoteExpiresAt(), "RESERVATION_EXPIRED", clock.instant());
                startCompensation(workflow, lease, "RESERVATION_EXPIRED");
                return;
            }
            if (location.state().equals(InventoryLocationReservationState.FAILED.name())) {
                startCompensation(workflow, lease, location.lastErrorCode());
                return;
            }
            if (location.state().equals(InventoryLocationReservationState.RELEASED.name())) continue;
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return;
            try {
                InventoryLocationReservationClient.RemoteReservation reserved = inventory.reserve(
                        location.fulfillmentLocationId(), workflow.orderId(), location.idempotencyKey(),
                        location.items().stream().map(item -> new InventoryLocationReservationClient.ReservationItem(
                                item.sellerId(), item.skuId(), item.quantity())).toList());
                if (!"RESERVED".equals(reserved.status())) {
                    boolean terminal = "EXPIRED".equals(reserved.status()) || "RELEASED".equals(reserved.status())
                            || "CANCELLED".equals(reserved.status());
                    throw new InventoryLocationClientException(terminal
                            ? InventoryLocationClientException.Failure.DEFINITIVE_REJECTION
                            : InventoryLocationClientException.Failure.RETRYABLE_UNKNOWN,
                            terminal ? "RESERVATION_" + reserved.status() : reserved.status(),
                            "Inventory location returned a non-reserved state");
                }
                if (!reservationUsable(reserved.expiresAt(), clock.instant())) {
                    if (!repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                            location.fulfillmentLocationId(), InventoryLocationReservationState.RELEASE_PENDING,
                            reserved.reservationId(), reserved.expiresAt(), "RESERVATION_EXPIRING", clock.instant())) return;
                    startCompensation(workflow, lease, "RESERVATION_EXPIRING");
                    return;
                }
                if (!repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), InventoryLocationReservationState.RESERVED,
                        reserved.reservationId(), reserved.expiresAt(), null, clock.instant())) return;
            } catch (InventoryLocationClientException exception) {
                if (exception.failure() == InventoryLocationClientException.Failure.DEFINITIVE_REJECTION) {
                    repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                            location.fulfillmentLocationId(), InventoryLocationReservationState.FAILED,
                            null, null, exception.providerCode(), clock.instant());
                    startCompensation(workflow, lease, exception.providerCode());
                    return;
                }
                if (exception.failure() == InventoryLocationClientException.Failure.PERMANENT) {
                    finish(workflow, lease.leaseToken(), OrderInventoryReservationWorkflowState.NEEDS_REVIEW,
                            exception.providerCode(), clock.instant());
                    return;
                }
                retry(workflow, location, lease.leaseToken(), exception.providerCode());
                return;
            }
        }
        WorkflowSnapshot current = repository.findByWorkflowId(workflow.workflowId());
        for (LocationSnapshot location : current.locations()) {
            if (location.state().equals(InventoryLocationReservationState.RESERVED.name())
                    && !reservationUsable(location, clock.instant())) {
                repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), InventoryLocationReservationState.RELEASE_PENDING,
                        location.reservationId(), location.remoteExpiresAt(), "RESERVATION_EXPIRED", clock.instant());
                startCompensation(current, lease, "RESERVATION_EXPIRED");
                return;
            }
        }
        finish(current, lease.leaseToken(), OrderInventoryReservationWorkflowState.RESERVED, null, clock.instant());

    }
    private void processPendingWorkflow(WorkflowSnapshot workflow, WorkLease lease) {
        List<LocationTransition> transitions = new ArrayList<>(workflow.locations().size());
        for (LocationSnapshot location : workflow.locations()) {
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return;
            try {
                InventoryLocationReservationClient.RemoteReservation reserved = inventory.reserve(
                        location.fulfillmentLocationId(), workflow.orderId(), location.idempotencyKey(),
                        location.items().stream().map(item -> new InventoryLocationReservationClient.ReservationItem(
                                item.sellerId(), item.skuId(), item.quantity())).toList());
                if (!"RESERVED".equals(reserved.status())) {
                    boolean definitive = "EXPIRED".equals(reserved.status()) || "RELEASED".equals(reserved.status())
                            || "CANCELLED".equals(reserved.status());
                    throw new InventoryLocationClientException(definitive
                            ? InventoryLocationClientException.Failure.DEFINITIVE_REJECTION
                            : InventoryLocationClientException.Failure.RETRYABLE_UNKNOWN,
                            definitive ? "RESERVATION_" + reserved.status() : reserved.status(),
                            "Inventory location returned a non-reserved state");
                }
                if (!reservationUsable(reserved.expiresAt(), clock.instant())) {
                    transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                            InventoryLocationReservationState.RELEASE_PENDING, reserved.reservationId(),
                            reserved.expiresAt(), "RESERVATION_EXPIRING"));
                    compensateAfterBatch(workflow, lease, transitions, "RESERVATION_EXPIRING");
                    return;
                }
                transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                        InventoryLocationReservationState.RESERVED, reserved.reservationId(),
                        reserved.expiresAt(), null));
            } catch (InventoryLocationClientException exception) {
                if (exception.failure() == InventoryLocationClientException.Failure.DEFINITIVE_REJECTION) {
                    transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                            InventoryLocationReservationState.FAILED, null, null, exception.providerCode()));
                    compensateAfterBatch(workflow, lease, transitions, exception.providerCode());
                    return;
                }
                if (exception.failure() == InventoryLocationClientException.Failure.PERMANENT) {
                    persistLocationTransitions(workflow, lease, transitions,
                            OrderInventoryReservationWorkflowState.NEEDS_REVIEW, exception.providerCode(),
                            clock.instant(), true);
                    return;
                }
                Instant now = clock.instant();
                transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                        InventoryLocationReservationState.RETRY_PENDING, location.reservationId(),
                        location.remoteExpiresAt(), exception.providerCode()));
                boolean exhausted = workflow.attemptCount() >= maxAttempts;
                OrderInventoryReservationWorkflowState state = exhausted
                        ? OrderInventoryReservationWorkflowState.NEEDS_REVIEW
                        : OrderInventoryReservationWorkflowState.RETRY_PENDING;
                Instant nextAttemptAt = exhausted ? now : now.plus(backoff(workflow.attemptCount()));
                persistLocationTransitions(workflow, lease, transitions, state,
                        exception.providerCode(), nextAttemptAt, true);
                return;
            }
        }

        Instant now = clock.instant();
        LocationTransition expired = transitions.stream()
                .filter(transition -> transition.state() == InventoryLocationReservationState.RESERVED
                        && !reservationUsable(transition.remoteExpiresAt(), now))
                .findFirst()
                .orElse(null);
        if (expired != null) {
            List<LocationTransition> compensating = transitions.stream()
                    .map(transition -> transition.fulfillmentLocationId().equals(expired.fulfillmentLocationId())
                            ? new LocationTransition(transition.fulfillmentLocationId(),
                                    InventoryLocationReservationState.RELEASE_PENDING,
                                    transition.reservationId(), transition.remoteExpiresAt(), "RESERVATION_EXPIRED")
                            : transition)
                    .toList();
            compensateAfterBatch(workflow, lease, compensating, "RESERVATION_EXPIRED");
            return;
        }
        persistLocationTransitions(workflow, lease, transitions,
                OrderInventoryReservationWorkflowState.RESERVED, null, now, true);
    }

    private void compensateAfterBatch(WorkflowSnapshot workflow, WorkLease lease,
            List<LocationTransition> transitions, String errorCode) {
        Instant now = clock.instant();
        if (!persistLocationTransitions(workflow, lease, transitions,
                OrderInventoryReservationWorkflowState.COMPENSATING, errorCode, now, false)) return;
        compensate(repository.findByWorkflowId(workflow.workflowId()), lease);
    }

    private boolean persistLocationTransitions(WorkflowSnapshot workflow, WorkLease lease,
            List<LocationTransition> transitions, OrderInventoryReservationWorkflowState state,
            String errorCode, Instant nextAttemptAt, boolean finishLease) {
        Instant now = clock.instant();
        Boolean persisted = transactions.execute(status -> {
            for (LocationTransition transition : transitions) {
                if (!repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        transition.fulfillmentLocationId(), transition.state(), transition.reservationId(),
                        transition.remoteExpiresAt(), transition.errorCode(), now)) {
                    status.setRollbackOnly();
                    return false;
                }
            }
            boolean workflowUpdated = finishLease
                    ? repository.finishWorkflow(workflow.workflowId(), lease.leaseToken(),
                            state, errorCode, nextAttemptAt, now)
                    : repository.updateWorkflowInFlight(workflow.workflowId(), lease.leaseToken(),
                            state, errorCode, nextAttemptAt, now);
            if (!workflowUpdated) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        boolean done = Boolean.TRUE.equals(persisted);
        if (done && finishLease) {
            settleAdmission(workflow, state, errorCode);
        }
        return done;
    }
    private void startCompensation(WorkflowSnapshot workflow, WorkLease lease, String errorCode) {
        Instant now = clock.instant();
        if (!repository.updateWorkflowInFlight(workflow.workflowId(), lease.leaseToken(),
                OrderInventoryReservationWorkflowState.COMPENSATING, errorCode, now, now)) return;
        compensate(repository.findByWorkflowId(workflow.workflowId()), lease);
    }

    private void compensate(WorkflowSnapshot workflow, WorkLease lease) {
        if (workflow == null) return;
        for (LocationSnapshot location : workflow.locations()) {
            if (location.state().equals(InventoryLocationReservationState.FAILED.name())
                    || location.state().equals(InventoryLocationReservationState.RELEASED.name())) continue;
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return;
            try {
                if (location.state().equals(InventoryLocationReservationState.CLAIMED.name())) {
                    // 매입 전 claim은 판매가 확정되지 않았으므로 판매 가능 수량으로 되돌린다.
                    inventory.restore(location.fulfillmentLocationId(), location.reservationId());
                } else {
                    cancelOrRestoreClaimed(workflow, location);
                }
                if (!repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), InventoryLocationReservationState.RELEASED,
                        location.reservationId(), location.remoteExpiresAt(), null, clock.instant())) return;
            } catch (InventoryLocationClientException exception) {
                if (exception.failure() == InventoryLocationClientException.Failure.PERMANENT) {
                    finish(workflow, lease.leaseToken(), OrderInventoryReservationWorkflowState.NEEDS_REVIEW,
                            exception.providerCode(), clock.instant());
                    return;
                }
                retryCompensation(workflow, location, lease.leaseToken(), exception.providerCode());
                return;
            }
        }
        OrderInventoryReservationWorkflowState released = cancelRequested(workflow)
                ? OrderInventoryReservationWorkflowState.CANCELLED
                : OrderInventoryReservationWorkflowState.FAILED;
        finish(workflow, lease.leaseToken(), released, workflow.lastErrorCode(), clock.instant());
    }

    /**
     * claim 응답이 유실되면 원격은 CLAIMED인데 로컬 위치는 RESERVED로 남는다. 원격 cancel은 CLAIMED 재고를
     * INVALID_STATE로 거부하므로, 그 거부는 "이미 claim됨"을 뜻하는 신호로 보고 restore로 판매 가능 수량을
     * 되돌린다. 매입 전이라 판매가 확정되지 않았다는 점은 CLAIMED 위치의 보상과 같다. restore 역시 CLAIMED가
     * 아니면 거부하므로 그 외 상태는 여전히 NEEDS_REVIEW로 드러난다.
     */
    private void cancelOrRestoreClaimed(WorkflowSnapshot workflow, LocationSnapshot location) {
        try {
            inventory.cancel(location.fulfillmentLocationId(), workflow.orderId(), location.idempotencyKey());
        } catch (InventoryLocationClientException exception) {
            if (exception.failure() != InventoryLocationClientException.Failure.PERMANENT
                    || !"INVALID_STATE".equals(exception.providerCode())) {
                throw exception;
            }
            inventory.restore(location.fulfillmentLocationId(), location.reservationId());
        }
    }

    private void retry(WorkflowSnapshot workflow, LocationSnapshot location, UUID leaseToken, String errorCode) {
        Instant now = clock.instant();
        if (workflow.attemptCount() >= maxAttempts) {
            repository.updateLocation(workflow.workflowId(), leaseToken, location.fulfillmentLocationId(),
                    InventoryLocationReservationState.RETRY_PENDING, location.reservationId(),
                    location.remoteExpiresAt(), errorCode, now);
            finish(workflow, leaseToken, OrderInventoryReservationWorkflowState.NEEDS_REVIEW, errorCode, now);
            return;
        }
        repository.updateLocation(workflow.workflowId(), leaseToken, location.fulfillmentLocationId(),
                InventoryLocationReservationState.RETRY_PENDING, location.reservationId(),
                location.remoteExpiresAt(), errorCode, now);
        Instant retryAt = now.plus(backoff(workflow.attemptCount()));
        finish(workflow, leaseToken, OrderInventoryReservationWorkflowState.RETRY_PENDING, errorCode, retryAt);
    }

    private void retryCompensation(WorkflowSnapshot workflow, LocationSnapshot location,
            UUID leaseToken, String errorCode) {
        Instant now = clock.instant();
        repository.updateLocation(workflow.workflowId(), leaseToken, location.fulfillmentLocationId(),
                InventoryLocationReservationState.RELEASE_PENDING, location.reservationId(),
                location.remoteExpiresAt(), errorCode, now);
        if (workflow.attemptCount() >= maxAttempts) {
            finish(workflow, leaseToken, OrderInventoryReservationWorkflowState.NEEDS_REVIEW, errorCode, now);
            return;
        }
        OrderInventoryReservationWorkflowState pending = cancelRequested(workflow)
                ? OrderInventoryReservationWorkflowState.CANCEL_PENDING
                : OrderInventoryReservationWorkflowState.COMPENSATION_REQUIRED;
        finish(workflow, leaseToken, pending, errorCode, now.plus(backoff(workflow.attemptCount())));
    }

    private boolean cancelRequested(WorkflowSnapshot workflow) {
        return workflow.state().equals(OrderInventoryReservationWorkflowState.CANCEL_PENDING.name());
    }

    private void finish(WorkflowSnapshot workflow, UUID leaseToken, OrderInventoryReservationWorkflowState state,
            String errorCode, Instant nextAttemptAt) {
        boolean finished = repository.finishWorkflow(workflow.workflowId(), leaseToken, state, errorCode,
                nextAttemptAt, clock.instant());
        if (finished) {
            settleAdmission(workflow, state, errorCode);
        }
    }

    /** 종결 상태가 되면 gate에 알려 승인 때 잡아 둔 수량을 정리하거나 돌려준다. gate 오류는 workflow를 막지 않는다. */
    private void settleAdmission(WorkflowSnapshot workflow, OrderInventoryReservationWorkflowState state,
            String errorCode) {
        if (admissionGate == null) {
            return;
        }
        try {
            switch (state) {
                case RESERVED, CLAIMED -> admissionGate.confirm(workflow.idempotencyKey());
                // 재고 부족으로 끝난 건은 돌려줄 재고가 없다. 승인 때 낙관적으로 잡았던 수량만 정리한다.
                case FAILED -> {
                    if ("INSUFFICIENT_STOCK".equals(errorCode)) {
                        admissionGate.confirm(workflow.idempotencyKey());
                    } else {
                        admissionGate.release(workflow.idempotencyKey());
                    }
                }
                case CANCELLED -> admissionGate.release(workflow.idempotencyKey());
                default -> { }
            }
        } catch (RuntimeException exception) {
            // gate는 정본이 아니다. 정리에 실패해도 stock TTL 재시드가 치유한다.
        }
    }

    private boolean reservationUsable(LocationSnapshot location, Instant now) {
        return reservationUsable(location.remoteExpiresAt(), now);
    }

    private boolean reservationUsable(Instant expiresAt, Instant now) {
        return expiresAt != null && expiresAt.isAfter(now.plus(minimumReservationRemaining));
    }

    private Duration backoff(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 10);
        long multiplier = 1L << shift;
        Duration delay = baseRetryDelay.multipliedBy(multiplier);
        if (delay.compareTo(maxRetryDelay) > 0) delay = maxRetryDelay;
        long jitterMillis = delay.toMillis() <= 1 ? 0
                : ThreadLocalRandom.current().nextLong(Math.max(1, delay.toMillis() / 4));
        return delay.plusMillis(jitterMillis);
    }

    private List<LocationPlan> normalize(List<LocationAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            throw invalid("At least one fulfillment location is required");
        }
        Map<UUID, TreeMap<UUID, TreeMap<String, Integer>>> grouped = new TreeMap<>();
        for (LocationAllocation allocation : allocations) {
            if (allocation == null || allocation.fulfillmentLocationId() == null
                    || allocation.items() == null || allocation.items().isEmpty()) {
                throw invalid("Each fulfillment location allocation must contain items");
            }
            for (Item item : allocation.items()) {
                if (item == null || item.sellerId() == null || item.skuId() == null
                        || !SKU_IDENTIFIER.matcher(item.skuId()).matches() || item.quantity() < 1) {
                    throw invalid("Seller, SKU, and positive quantity are required");
                }
                try {
                    grouped.computeIfAbsent(allocation.fulfillmentLocationId(), ignored -> new TreeMap<>())
                            .computeIfAbsent(item.sellerId(), ignored -> new TreeMap<>())
                            .merge(item.skuId(), item.quantity(), Math::addExact);
                } catch (ArithmeticException exception) {
                    throw invalid("Aggregated inventory quantity is out of range");
                }
            }
        }
        List<LocationPlan> plans = new ArrayList<>(grouped.size());
        grouped.forEach((locationId, sellerItems) -> {
            List<ItemRecord> items = new ArrayList<>();
            sellerItems.forEach((sellerId, skuItems) -> skuItems.forEach((skuId, quantity) ->
                    items.add(new ItemRecord(sellerId, skuId, quantity))));
            plans.add(new LocationPlan(locationId, List.copyOf(items)));
        });
        return List.copyOf(plans);
    }
    private boolean samePlans(WorkflowSnapshot workflow, List<LocationPlan> plans) {
        if (workflow.locations().size() != plans.size()) return false;
        List<LocationPlan> existing = workflow.locations().stream()
                .map(location -> new LocationPlan(location.fulfillmentLocationId(), location.items()))
                .toList();
        return existing.equals(plans);
    }

    private OrderInventoryReservationResponse response(WorkflowSnapshot workflow, boolean replayed) {
        List<LocationResult> locations = workflow.locations().stream()
                .map(location -> new LocationResult(location.fulfillmentLocationId(), location.state(),
                        location.reservationId(), location.remoteExpiresAt(), location.lastErrorCode(),
                        location.items().stream().map(item -> new OrderInventoryReservationResponse.Item(
                                item.sellerId(), item.skuId(), item.quantity())).toList()))
                .toList();
        return new OrderInventoryReservationResponse(workflow.workflowId(), workflow.orderId(),
                workflow.idempotencyKey(), workflow.state(), locations, workflow.createdAt(),
                workflow.updatedAt(), replayed);
    }

    private boolean terminal(String state) {
        return state.equals(OrderInventoryReservationWorkflowState.RESERVED.name())
                || state.equals(OrderInventoryReservationWorkflowState.FAILED.name())
                || state.equals(OrderInventoryReservationWorkflowState.CANCELLED.name())
                || state.equals(OrderInventoryReservationWorkflowState.CLAIMED.name())
                || state.equals(OrderInventoryReservationWorkflowState.NEEDS_REVIEW.name());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw invalid("Idempotency-Key must contain between 1 and 128 characters");
        }
    }

    private OrderException invalid(String message) {
        return new OrderException(OrderException.Code.INVALID_REQUEST, message);
    }

    private OrderException conflict(String message) {
        return new OrderException(OrderException.Code.CONFLICT, message);
    }
}
