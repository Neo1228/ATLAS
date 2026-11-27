package com.atlas.order.service.impl;

import com.atlas.order.dto.request.LocationReservationItemRequest;
import com.atlas.order.dto.request.LocationReservationRequest.Item;
import com.atlas.order.dto.request.LocationReservationRequest.LocationAllocation;
import com.atlas.order.dto.request.LocationReservationRequest;
import com.atlas.order.dto.request.StockClaimRequest;
import com.atlas.order.dto.response.ReservationWorkflowResponse.LocationReservation.LocationResult;
import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.exception.LocationClientException;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.LocationReservationState;
import com.atlas.order.model.LocationReservationWorkflowState;
import com.atlas.order.model.RemoteReservationStatus;
import com.atlas.order.persistence.projection.ReservationWorkflowItemRecord;
import com.atlas.order.persistence.projection.ReservationWorkflowLease;
import com.atlas.order.persistence.projection.ReservationWorkflowLocationPlan;
import com.atlas.order.persistence.projection.ReservationWorkflowLocationSnapshot;
import com.atlas.order.persistence.projection.ReservationWorkflowSnapshot;
import com.atlas.order.repository.LocationReservationRepository;
import com.atlas.order.service.LocationClient;
import com.atlas.order.service.LocationReservationService.ClaimOutcome;
import com.atlas.order.service.LocationReservationService;
import com.atlas.order.service.StockAdmissionControl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class LocationReservationServiceImpl implements LocationReservationService {
    /**
     * 이 서비스가 workflow 의 {@code last_error_code} 에 직접 기록하는 코드. 원격이 돌려주는 코드
     * ({@link LocationClientException#RESERVATION_EXPIRED} 등)와 이름이 겹치지만 의미가 다르다: 원격 만료는
     * 원격이 알려 준 사실이고 여기 코드는 이 서비스가 deadline 으로 판단한 결과다.
     */
    private static final String ERROR_RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    private static final String ERROR_RESERVATION_EXPIRING = "RESERVATION_EXPIRING";
    private static final String ERROR_CLAIM_RESULT_UNKNOWN = "CLAIM_RESULT_UNKNOWN";

    private static final Pattern SKU_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,96}");

    private final LocationReservationRepository repository;
    private final LocationClient inventory;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration leaseRenewalThreshold;
    private final Duration baseRetryDelay;
    private final Duration maxRetryDelay;
    private final Duration minimumReservationRemaining;
    private final int maxAttempts;

    public LocationReservationServiceImpl(LocationReservationRepository repository,
            LocationClient inventory,
            Clock clock,
            PlatformTransactionManager transactionManager,
            Duration leaseDuration,
            Duration baseRetryDelay,
            Duration maxRetryDelay,
            Duration minimumReservationRemaining,
            int maxAttempts,
            Optional<StockAdmissionControl> stockAdmission) {
        this.stockAdmission = stockAdmission;
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

    @Override
    public ReservationWorkflowResponse.LocationReservation stage(String idempotencyKey,
            LocationReservationRequest request) {
        UUID candidateId = UUID.randomUUID();
        ReservationWorkflowSnapshot workflow = persistIntent(candidateId, idempotencyKey, request);
        return response(workflow, !workflow.workflowId().equals(candidateId));
    }

    @Override
    public ReservationWorkflowResponse.LocationReservation submit(String idempotencyKey,
            LocationReservationRequest request) {
        rejectIfAdmissionExhausted(idempotencyKey, request);
        UUID candidateId = UUID.randomUUID();
        SubmissionAttempt attempt = transactions.execute(status -> {
            ReservationWorkflowSnapshot workflow = persistIntent(candidateId, idempotencyKey, request);
            boolean replayed = !workflow.workflowId().equals(candidateId);
            if (terminal(workflow.state())) return new SubmissionAttempt(workflow, null, replayed);
            Instant now = clock.instant();
            Instant leaseUntil = now.plus(leaseDuration);
            UUID leaseToken = repository.claimWorkflow(workflow.workflowId(), now, leaseUntil);
            ReservationWorkflowLease lease = leaseToken == null ? null
                    : new ReservationWorkflowLease(workflow.workflowId(), leaseToken, leaseUntil);
            return new SubmissionAttempt(workflow, lease, replayed);
        });
        ReservationWorkflowSnapshot workflow = attempt.workflow();
        if (terminal(workflow.state())) return response(workflow, true);
        ReservationWorkflowLease lease = attempt.lease();
        if (lease == null) return response(repository.findByWorkflowId(workflow.workflowId()), true);
        process(lease);
        return response(repository.findByWorkflowId(workflow.workflowId()), attempt.replayed());
    }

    private ReservationWorkflowSnapshot persistIntent(UUID candidateId, String idempotencyKey,
            LocationReservationRequest request) {
        validateIdempotencyKey(idempotencyKey);
        if (request == null || request.orderId() == null) throw invalid("Order id is required");
        List<ReservationWorkflowLocationPlan> plans = normalize(request.allocations());
        if (plans.isEmpty()) throw invalid("At least one fulfillment location is required");

        ReservationWorkflowSnapshot workflow = repository.save(
                candidateId, request.orderId(), idempotencyKey, plans, clock.instant());
        if (workflow == null) throw new IllegalStateException("Inventory reservation workflow disappeared");
        if (!workflow.orderId().equals(request.orderId()) || !samePlans(workflow, plans)) {
            throw conflict("Idempotency key was used for different inventory reservation facts");
        }
        return workflow;
    }

    private final Optional<StockAdmissionControl> stockAdmission;

    /** 선택 구성요소다. 없으면 모든 요청이 기존 경로를 그대로 탄다. */

    private void rejectIfAdmissionExhausted(String idempotencyKey, LocationReservationRequest request) {
        if (stockAdmission.isEmpty() || idempotencyKey == null || request == null || request.allocations() == null) {
            return;
        }
        List<StockClaimRequest> claims = request.allocations().stream()
                .flatMap(allocation -> allocation.items().stream().map(item -> new StockClaimRequest(
                        allocation.fulfillmentLocationId(), item.sellerId(), item.skuId(), item.quantity())))
                .toList();
        if (!claims.isEmpty() && !stockAdmission.get().admit(idempotencyKey, claims)) {
            // 확실히 소진된 SKU다. workflow 행도 원격 호출도 만들지 않고 즉시 재고 부족으로 답한다.
            throw conflict("Inventory appears exhausted at the admission gate; the reservation was not attempted");
        }
    }

    @Override
    public ReservationWorkflowResponse.LocationReservation find(UUID workflowId) {
        ReservationWorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        return response(workflow, true);
    }

    @Override
    public ReservationWorkflowResponse.LocationReservation retry(UUID workflowId) {
        ReservationWorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        if (terminal(workflow.state())) return response(workflow, true);
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(leaseDuration);
        UUID leaseToken = repository.claimWorkflowForRetry(workflowId, now, leaseUntil);
        if (leaseToken != null) process(new ReservationWorkflowLease(workflowId, leaseToken, leaseUntil));
        return response(repository.findByWorkflowId(workflowId), true);
    }

    @Override
    public List<ReservationWorkflowResponse.LocationReservation> needsReview(int limit) {
        if (limit < 1 || limit > 200) throw invalid("limit must be between 1 and 200");
        return repository.findNeedsReviewIds(limit).stream().map(this::find).toList();
    }

    @Override
    public ReservationWorkflowResponse.LocationReservation releaseNeedsReview(UUID workflowId) {
        ReservationWorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        Instant now = clock.instant();
        if (repository.requeueNeedsReviewForCompensation(workflowId, now)) {
            UUID leaseToken = repository.claimWorkflowForRetry(workflowId, now, now.plus(leaseDuration));
            if (leaseToken != null) process(new ReservationWorkflowLease(workflowId, leaseToken, now.plus(leaseDuration)));
        }
        return response(repository.findByWorkflowId(workflowId), true);
    }

    @Override
    public ReservationWorkflowResponse.LocationReservation cancel(UUID workflowId) {
        ReservationWorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        if (workflow.state() == LocationReservationWorkflowState.CANCELLED
                || workflow.state() == LocationReservationWorkflowState.FAILED
                || workflow.state() == LocationReservationWorkflowState.NEEDS_REVIEW
                || cancelRequested(workflow)) {
            // 이미 요청된 취소는 recovery worker가 backoff에 따라 재시도하므로 반복 호출이 원격 호출을 늘리지 않는다.
            return response(workflow, true);
        }

        Instant now = clock.instant();
        repository.requestCancellation(workflowId, now);
        Instant leaseUntil = now.plus(leaseDuration);
        UUID leaseToken = repository.claimWorkflow(workflowId, now, leaseUntil);
        if (leaseToken != null) process(new ReservationWorkflowLease(workflowId, leaseToken, leaseUntil));
        return response(repository.findByWorkflowId(workflowId), true);
    }

    @Override
    public ClaimOutcome claim(UUID workflowId) {
        ReservationWorkflowSnapshot workflow = repository.findByWorkflowId(workflowId);
        if (workflow == null) throw new OrderException(OrderException.Code.NOT_FOUND,
                "Inventory reservation workflow not found");
        if (workflow.state() != LocationReservationWorkflowState.RESERVED) return recordedClaimOutcome(workflow);
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(leaseDuration);
        UUID leaseToken = repository.leaseForClaim(workflowId, now, leaseUntil);
        if (leaseToken == null) return ClaimOutcome.PENDING;
        ReservationWorkflowLease lease = new ReservationWorkflowLease(workflowId, leaseToken, leaseUntil);
        for (ReservationWorkflowLocationSnapshot location : workflow.locations()) {
            if (location.state() == LocationReservationState.CLAIMED) continue;
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return ClaimOutcome.PENDING;
            try {
                ReservationWorkflowResponse.RemoteReservation claimed =
                        inventory.claim(location.fulfillmentLocationId(), location.reservationId());
                if (claimed.status() != RemoteReservationStatus.CLAIMED) {
                    finish(workflow, lease.leaseToken(), LocationReservationWorkflowState.RESERVED,
                            ERROR_CLAIM_RESULT_UNKNOWN, clock.instant());
                    return ClaimOutcome.PENDING;
                }
                repository.updateLocation(workflowId, lease.leaseToken(), location.fulfillmentLocationId(),
                        LocationReservationState.CLAIMED, location.reservationId(),
                        location.remoteExpiresAt(), null, clock.instant());
            } catch (LocationClientException exception) {
                finish(workflow, lease.leaseToken(), LocationReservationWorkflowState.RESERVED,
                        exception.providerCode(), clock.instant());
                return exception.failure() == LocationClientException.Failure.RETRYABLE_UNKNOWN
                        ? ClaimOutcome.PENDING : ClaimOutcome.REJECTED;
            }
        }
        if (!finish(workflow, lease.leaseToken(), LocationReservationWorkflowState.CLAIMED, null, clock.instant())) {
            // lease를 잃어 CLAIMED를 기록하지 못했다. 그 사이 다른 coordinator가 취소하고 재고를 되돌렸을 수 있으므로
            // 원격 응답이 아니라 기록된 상태로 답한다. 호출자는 CLAIMED일 때만 매입한다.
            return recordedClaimOutcome(repository.findByWorkflowId(workflowId));
        }
        return ClaimOutcome.CLAIMED;
    }

    /**
     * 기록된 workflow 상태로 claim 결과를 정한다. 취소 요청과 종결 상태는 REJECTED(호출자가 승인을 void),
     * RESERVED(다른 claim이 lease를 쥠)와 보상 진행 중은 PENDING이다.
     */
    private ClaimOutcome recordedClaimOutcome(ReservationWorkflowSnapshot workflow) {
        if (workflow.state() == LocationReservationWorkflowState.CLAIMED) return ClaimOutcome.CLAIMED;
        if (workflow.state() == LocationReservationWorkflowState.RESERVED) return ClaimOutcome.PENDING;
        return terminal(workflow.state()) || cancelRequested(workflow) ? ClaimOutcome.REJECTED : ClaimOutcome.PENDING;
    }

    private record SubmissionAttempt(ReservationWorkflowSnapshot workflow, ReservationWorkflowLease lease, boolean replayed) {}
    private record LocationTransition(
            UUID fulfillmentLocationId,
            LocationReservationState state,
            UUID reservationId,
            Instant remoteExpiresAt,
            String errorCode) {}

    @Override
    public long needsReviewCount(boolean claimedResidual) {
        return repository.countNeedsReview(claimedResidual);
    }

    @Override
    public double oldestDueAgeSeconds() {
        return repository.oldestDueAgeSeconds(clock.instant());
    }

    @Override
    public int recoverDue(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw invalid("Recovery batch size must be between 1 and 1000");
        Instant dueThrough = clock.instant();
        int processed = 0;
        while (processed < batchSize) {
            Instant now = clock.instant();
            ReservationWorkflowLease lease = repository.claimNextDue(dueThrough, now, now.plus(leaseDuration));
            if (lease == null) break;
            process(lease);
            processed++;
        }
        return processed;
    }

    private ReservationWorkflowLease renewLeaseIfNeeded(ReservationWorkflowLease lease) {
        Instant now = clock.instant();
        if (lease.leaseUntil().isAfter(now.plus(leaseRenewalThreshold))) return lease;
        Instant leaseUntil = now.plus(leaseDuration);
        return repository.renewLease(lease.workflowId(), lease.leaseToken(), now, leaseUntil)
                ? new ReservationWorkflowLease(lease.workflowId(), lease.leaseToken(), leaseUntil) : null;
    }

    private void process(ReservationWorkflowLease lease) {
        ReservationWorkflowSnapshot workflow = repository.findByWorkflowId(lease.workflowId());
        if (workflow == null) return;
        if (workflow.state() == LocationReservationWorkflowState.COMPENSATING
                || workflow.state() == LocationReservationWorkflowState.COMPENSATION_REQUIRED
                || workflow.state() == LocationReservationWorkflowState.CANCEL_PENDING) {
            compensate(workflow, lease);
            return;
        }
        if (workflow.state() == LocationReservationWorkflowState.RESERVED
                || workflow.state() == LocationReservationWorkflowState.FAILED
                || workflow.state() == LocationReservationWorkflowState.NEEDS_REVIEW) {
            repository.finishWorkflow(workflow.workflowId(), lease.leaseToken(),
                    workflow.state(), workflow.lastErrorCode(),
                    workflow.nextAttemptAt(), clock.instant());
            return;
        }

        if ((workflow.state() == LocationReservationWorkflowState.PENDING
                || workflow.state() == LocationReservationWorkflowState.RETRY_PENDING)
                && workflow.locations().stream().allMatch(location ->
                location.state() == LocationReservationState.PENDING)) {
            processPendingWorkflow(workflow, lease);
            return;
        }

        for (ReservationWorkflowLocationSnapshot location : workflow.locations()) {
            if (location.state() == LocationReservationState.RESERVED) {
                if (reservationUsable(location, clock.instant())) continue;
                repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), LocationReservationState.RELEASE_PENDING,
                        location.reservationId(), location.remoteExpiresAt(), ERROR_RESERVATION_EXPIRED, clock.instant());
                startCompensation(workflow, lease, ERROR_RESERVATION_EXPIRED);
                return;
            }
            if (location.state() == LocationReservationState.FAILED) {
                startCompensation(workflow, lease, location.lastErrorCode());
                return;
            }
            if (location.state() == LocationReservationState.RELEASED) continue;
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return;
            try {
                ReservationWorkflowResponse.RemoteReservation reserved = inventory.reserve(
                        location.fulfillmentLocationId(), workflow.orderId(), location.idempotencyKey(),
                        location.items().stream().map(item -> new LocationReservationItemRequest(
                                item.sellerId(), item.skuId(), item.quantity())).toList());
                requireReserved(reserved);
                if (!reservationUsable(reserved.expiresAt(), clock.instant())) {
                    if (!repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                            location.fulfillmentLocationId(), LocationReservationState.RELEASE_PENDING,
                            reserved.reservationId(), reserved.expiresAt(), ERROR_RESERVATION_EXPIRING, clock.instant())) return;
                    startCompensation(workflow, lease, ERROR_RESERVATION_EXPIRING);
                    return;
                }
                if (!repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), LocationReservationState.RESERVED,
                        reserved.reservationId(), reserved.expiresAt(), null, clock.instant())) return;
            } catch (LocationClientException exception) {
                if (exception.failure() == LocationClientException.Failure.DEFINITIVE_REJECTION) {
                    repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                            location.fulfillmentLocationId(), LocationReservationState.FAILED,
                            null, null, exception.providerCode(), clock.instant());
                    startCompensation(workflow, lease, exception.providerCode());
                    return;
                }
                if (exception.failure() == LocationClientException.Failure.PERMANENT) {
                    finish(workflow, lease.leaseToken(), LocationReservationWorkflowState.NEEDS_REVIEW,
                            exception.providerCode(), clock.instant());
                    return;
                }
                retry(workflow, location, lease.leaseToken(), exception.providerCode());
                return;
            }
        }
        ReservationWorkflowSnapshot current = repository.findByWorkflowId(workflow.workflowId());
        for (ReservationWorkflowLocationSnapshot location : current.locations()) {
            if (location.state() == LocationReservationState.RESERVED
                    && !reservationUsable(location, clock.instant())) {
                repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), LocationReservationState.RELEASE_PENDING,
                        location.reservationId(), location.remoteExpiresAt(), ERROR_RESERVATION_EXPIRED, clock.instant());
                startCompensation(current, lease, ERROR_RESERVATION_EXPIRED);
                return;
            }
        }
        finish(current, lease.leaseToken(), LocationReservationWorkflowState.RESERVED, null, clock.instant());
    }

    /**
     * reserve 응답이 RESERVED 가 아니면 예외로 바꾼다. 끝난 예약(RELEASED/EXPIRED/CANCELLED)은 같은 key 로 다시
     * 보내도 재고를 잡지 못하므로 확정 거절이고, CLAIMED 처럼 끝나지 않은 다른 상태는 다시 물어야 결과를 알 수 있다.
     */
    private static void requireReserved(ReservationWorkflowResponse.RemoteReservation reserved) {
        RemoteReservationStatus status = reserved.status();
        if (status == RemoteReservationStatus.RESERVED) return;
        throw new LocationClientException(status.isTerminal()
                ? LocationClientException.Failure.DEFINITIVE_REJECTION
                : LocationClientException.Failure.RETRYABLE_UNKNOWN,
                status.isTerminal() ? "RESERVATION_" + status.name() : status.name(),
                "Inventory location returned a non-reserved state");
    }

    private void processPendingWorkflow(ReservationWorkflowSnapshot workflow, ReservationWorkflowLease lease) {
        List<LocationTransition> transitions = new ArrayList<>(workflow.locations().size());
        for (ReservationWorkflowLocationSnapshot location : workflow.locations()) {
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return;
            try {
                ReservationWorkflowResponse.RemoteReservation reserved = inventory.reserve(
                        location.fulfillmentLocationId(), workflow.orderId(), location.idempotencyKey(),
                        location.items().stream().map(item -> new LocationReservationItemRequest(
                                item.sellerId(), item.skuId(), item.quantity())).toList());
                requireReserved(reserved);
                if (!reservationUsable(reserved.expiresAt(), clock.instant())) {
                    transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                            LocationReservationState.RELEASE_PENDING, reserved.reservationId(),
                            reserved.expiresAt(), ERROR_RESERVATION_EXPIRING));
                    compensateAfterBatch(workflow, lease, transitions, ERROR_RESERVATION_EXPIRING);
                    return;
                }
                transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                        LocationReservationState.RESERVED, reserved.reservationId(),
                        reserved.expiresAt(), null));
            } catch (LocationClientException exception) {
                if (exception.failure() == LocationClientException.Failure.DEFINITIVE_REJECTION) {
                    transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                            LocationReservationState.FAILED, null, null, exception.providerCode()));
                    compensateAfterBatch(workflow, lease, transitions, exception.providerCode());
                    return;
                }
                if (exception.failure() == LocationClientException.Failure.PERMANENT) {
                    persistLocationTransitions(workflow, lease, transitions,
                            LocationReservationWorkflowState.NEEDS_REVIEW, exception.providerCode(),
                            clock.instant(), true);
                    return;
                }
                Instant now = clock.instant();
                transitions.add(new LocationTransition(location.fulfillmentLocationId(),
                        LocationReservationState.RETRY_PENDING, location.reservationId(),
                        location.remoteExpiresAt(), exception.providerCode()));
                boolean exhausted = workflow.attemptCount() >= maxAttempts;
                LocationReservationWorkflowState state = exhausted
                        ? LocationReservationWorkflowState.NEEDS_REVIEW
                        : LocationReservationWorkflowState.RETRY_PENDING;
                Instant nextAttemptAt = exhausted ? now : now.plus(backoff(workflow.attemptCount()));
                persistLocationTransitions(workflow, lease, transitions, state,
                        exception.providerCode(), nextAttemptAt, true);
                return;
            }
        }

        Instant now = clock.instant();
        LocationTransition expired = transitions.stream()
                .filter(transition -> transition.state() == LocationReservationState.RESERVED
                        && !reservationUsable(transition.remoteExpiresAt(), now))
                .findFirst()
                .orElse(null);
        if (expired != null) {
            List<LocationTransition> compensating = transitions.stream()
                    .map(transition -> transition.fulfillmentLocationId().equals(expired.fulfillmentLocationId())
                            ? new LocationTransition(transition.fulfillmentLocationId(),
                                    LocationReservationState.RELEASE_PENDING,
                                    transition.reservationId(), transition.remoteExpiresAt(), ERROR_RESERVATION_EXPIRED)
                            : transition)
                    .toList();
            compensateAfterBatch(workflow, lease, compensating, ERROR_RESERVATION_EXPIRED);
            return;
        }
        persistLocationTransitions(workflow, lease, transitions,
                LocationReservationWorkflowState.RESERVED, null, now, true);
    }

    private void compensateAfterBatch(ReservationWorkflowSnapshot workflow, ReservationWorkflowLease lease,
            List<LocationTransition> transitions, String errorCode) {
        Instant now = clock.instant();
        if (!persistLocationTransitions(workflow, lease, transitions,
                LocationReservationWorkflowState.COMPENSATING, errorCode, now, false)) return;
        compensate(repository.findByWorkflowId(workflow.workflowId()), lease);
    }

    private boolean persistLocationTransitions(ReservationWorkflowSnapshot workflow, ReservationWorkflowLease lease,
            List<LocationTransition> transitions, LocationReservationWorkflowState state,
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
    private void startCompensation(ReservationWorkflowSnapshot workflow, ReservationWorkflowLease lease, String errorCode) {
        Instant now = clock.instant();
        if (!repository.updateWorkflowInFlight(workflow.workflowId(), lease.leaseToken(),
                LocationReservationWorkflowState.COMPENSATING, errorCode, now, now)) return;
        compensate(repository.findByWorkflowId(workflow.workflowId()), lease);
    }

    private void compensate(ReservationWorkflowSnapshot workflow, ReservationWorkflowLease lease) {
        if (workflow == null) return;
        for (ReservationWorkflowLocationSnapshot location : workflow.locations()) {
            if (location.state() == LocationReservationState.FAILED
                    || location.state() == LocationReservationState.RELEASED) continue;
            lease = renewLeaseIfNeeded(lease);
            if (lease == null) return;
            try {
                if (location.state() == LocationReservationState.CLAIMED) {
                    // 매입 전 claim은 판매가 확정되지 않았으므로 판매 가능 수량으로 되돌린다.
                    inventory.restore(location.fulfillmentLocationId(), location.reservationId());
                } else {
                    cancelOrRestoreClaimed(workflow, location);
                }
                if (!repository.updateLocation(workflow.workflowId(), lease.leaseToken(),
                        location.fulfillmentLocationId(), LocationReservationState.RELEASED,
                        location.reservationId(), location.remoteExpiresAt(), null, clock.instant())) return;
            } catch (LocationClientException exception) {
                if (exception.failure() == LocationClientException.Failure.PERMANENT) {
                    finish(workflow, lease.leaseToken(), LocationReservationWorkflowState.NEEDS_REVIEW,
                            exception.providerCode(), clock.instant());
                    return;
                }
                retryCompensation(workflow, location, lease.leaseToken(), exception.providerCode());
                return;
            }
        }
        LocationReservationWorkflowState released = cancelRequested(workflow)
                ? LocationReservationWorkflowState.CANCELLED
                : LocationReservationWorkflowState.FAILED;
        finish(workflow, lease.leaseToken(), released, workflow.lastErrorCode(), clock.instant());
    }

    /**
     * claim 응답이 유실되면 원격은 CLAIMED인데 로컬 위치는 RESERVED로 남는다. 원격 cancel은 CLAIMED 재고를
     * INVALID_STATE로 거부하므로, 그 거부는 "이미 claim됨"을 뜻하는 신호로 보고 restore로 판매 가능 수량을
     * 되돌린다. 매입 전이라 판매가 확정되지 않았다는 점은 CLAIMED 위치의 보상과 같다. restore 역시 CLAIMED가
     * 아니면 거부하므로 그 외 상태는 여전히 NEEDS_REVIEW로 드러난다.
     */
    private void cancelOrRestoreClaimed(ReservationWorkflowSnapshot workflow, ReservationWorkflowLocationSnapshot location) {
        try {
            inventory.cancel(location.fulfillmentLocationId(), workflow.orderId(), location.idempotencyKey());
        } catch (LocationClientException exception) {
            if (exception.failure() != LocationClientException.Failure.PERMANENT || !exception.isInvalidState()) {
                throw exception;
            }
            inventory.restore(location.fulfillmentLocationId(), location.reservationId());
        }
    }

    private void retry(ReservationWorkflowSnapshot workflow, ReservationWorkflowLocationSnapshot location, UUID leaseToken, String errorCode) {
        Instant now = clock.instant();
        if (workflow.attemptCount() >= maxAttempts) {
            repository.updateLocation(workflow.workflowId(), leaseToken, location.fulfillmentLocationId(),
                    LocationReservationState.RETRY_PENDING, location.reservationId(),
                    location.remoteExpiresAt(), errorCode, now);
            finish(workflow, leaseToken, LocationReservationWorkflowState.NEEDS_REVIEW, errorCode, now);
            return;
        }
        repository.updateLocation(workflow.workflowId(), leaseToken, location.fulfillmentLocationId(),
                LocationReservationState.RETRY_PENDING, location.reservationId(),
                location.remoteExpiresAt(), errorCode, now);
        Instant retryAt = now.plus(backoff(workflow.attemptCount()));
        finish(workflow, leaseToken, LocationReservationWorkflowState.RETRY_PENDING, errorCode, retryAt);
    }

    private void retryCompensation(ReservationWorkflowSnapshot workflow, ReservationWorkflowLocationSnapshot location,
            UUID leaseToken, String errorCode) {
        Instant now = clock.instant();
        repository.updateLocation(workflow.workflowId(), leaseToken, location.fulfillmentLocationId(),
                LocationReservationState.RELEASE_PENDING, location.reservationId(),
                location.remoteExpiresAt(), errorCode, now);
        if (workflow.attemptCount() >= maxAttempts) {
            finish(workflow, leaseToken, LocationReservationWorkflowState.NEEDS_REVIEW, errorCode, now);
            return;
        }
        LocationReservationWorkflowState pending = cancelRequested(workflow)
                ? LocationReservationWorkflowState.CANCEL_PENDING
                : LocationReservationWorkflowState.COMPENSATION_REQUIRED;
        finish(workflow, leaseToken, pending, errorCode, now.plus(backoff(workflow.attemptCount())));
    }

    private boolean cancelRequested(ReservationWorkflowSnapshot workflow) {
        return workflow.state() == LocationReservationWorkflowState.CANCEL_PENDING;
    }

    private boolean finish(ReservationWorkflowSnapshot workflow, UUID leaseToken, LocationReservationWorkflowState state,
            String errorCode, Instant nextAttemptAt) {
        boolean finished = repository.finishWorkflow(workflow.workflowId(), leaseToken, state, errorCode,
                nextAttemptAt, clock.instant());
        if (finished) {
            settleAdmission(workflow, state, errorCode);
        }
        return finished;
    }

    /** 종결 상태가 되면 gate에 알려 승인 때 잡아 둔 수량을 정리하거나 돌려준다. gate 오류는 workflow를 막지 않는다. */
    private void settleAdmission(ReservationWorkflowSnapshot workflow, LocationReservationWorkflowState state,
            String errorCode) {
        if (stockAdmission.isEmpty()) {
            return;
        }
        try {
            switch (state) {
                case RESERVED, CLAIMED -> stockAdmission.get().confirm(workflow.idempotencyKey());
                // 재고 부족으로 끝난 건은 돌려줄 재고가 없다. 승인 때 낙관적으로 잡았던 수량만 정리한다.
                case FAILED -> {
                    if (LocationClientException.INSUFFICIENT_STOCK.equals(errorCode)) {
                        stockAdmission.get().confirm(workflow.idempotencyKey());
                    } else {
                        stockAdmission.get().release(workflow.idempotencyKey());
                    }
                }
                case CANCELLED -> stockAdmission.get().release(workflow.idempotencyKey());
                default -> { }
            }
        } catch (RuntimeException exception) {
            // gate는 정본이 아니다. 정리에 실패해도 stock TTL 재시드가 치유한다.
        }
    }

    private boolean reservationUsable(ReservationWorkflowLocationSnapshot location, Instant now) {
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

    private List<ReservationWorkflowLocationPlan> normalize(List<LocationAllocation> allocations) {
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
        List<ReservationWorkflowLocationPlan> plans = new ArrayList<>(grouped.size());
        grouped.forEach((locationId, sellerItems) -> {
            List<ReservationWorkflowItemRecord> items = new ArrayList<>();
            sellerItems.forEach((sellerId, skuItems) -> skuItems.forEach((skuId, quantity) ->
                    items.add(new ReservationWorkflowItemRecord(sellerId, skuId, quantity))));
            plans.add(new ReservationWorkflowLocationPlan(locationId, List.copyOf(items)));
        });
        return List.copyOf(plans);
    }
    private boolean samePlans(ReservationWorkflowSnapshot workflow, List<ReservationWorkflowLocationPlan> plans) {
        if (workflow.locations().size() != plans.size()) return false;
        List<ReservationWorkflowLocationPlan> existing = workflow.locations().stream()
                .map(location -> new ReservationWorkflowLocationPlan(location.fulfillmentLocationId(), location.items()))
                .toList();
        return existing.equals(plans);
    }

    private ReservationWorkflowResponse.LocationReservation response(ReservationWorkflowSnapshot workflow, boolean replayed) {
        List<LocationResult> locations = workflow.locations().stream()
                .map(location -> new LocationResult(location.fulfillmentLocationId(), location.state(),
                        location.reservationId(), location.remoteExpiresAt(), location.lastErrorCode(),
                        location.items().stream().map(item -> new ReservationWorkflowResponse.LocationReservation.Item(
                                item.sellerId(), item.skuId(), item.quantity())).toList()))
                .toList();
        return new ReservationWorkflowResponse.LocationReservation(workflow.workflowId(), workflow.orderId(),
                workflow.idempotencyKey(), workflow.state(), locations, workflow.createdAt(),
                workflow.updatedAt(), replayed);
    }

    private boolean terminal(LocationReservationWorkflowState state) {
        return state == LocationReservationWorkflowState.RESERVED
                || state == LocationReservationWorkflowState.FAILED
                || state == LocationReservationWorkflowState.CANCELLED
                || state == LocationReservationWorkflowState.CLAIMED
                || state == LocationReservationWorkflowState.NEEDS_REVIEW;
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
