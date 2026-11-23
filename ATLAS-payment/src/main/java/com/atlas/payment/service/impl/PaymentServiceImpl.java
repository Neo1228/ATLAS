package com.atlas.payment.service.impl;

import static com.atlas.payment.service.impl.PaymentSupport.invalidRequest;
import static com.atlas.payment.service.impl.PaymentSupport.notFound;
import static com.atlas.payment.service.impl.PaymentSupport.validateOwnerAndKey;

import com.atlas.payment.provider.PaymentProviderResult;
import com.atlas.payment.provider.PaymentOperation;
import com.atlas.payment.provider.PaymentRequest;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.service.FxConversionService;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final OwnerLockRegistry ownerLocks = new OwnerLockRegistry();
    private final PaymentRefundWorkflow refundWorkflow;
    private final PaymentWebhookProcessor webhookProcessor;

    private final PaymentAttemptRepository repository;
    private final PaymentRecoveryRepository recovery;
    private final TransactionTemplate transactions;
    private final PaymentProvider psp;
    private final FxConversionService fxConversionService;
    private final String publicBaseUrl;
    private final Duration recoveryInitialDelay;
    private final Duration approvalTtl;
    private final Supplier<UUID> ids;

    @Autowired
    public PaymentServiceImpl(PaymentAttemptRepository repository, PaymentRefundRepository refunds,
            PaymentWebhookEventRepository webhookEvents, PaymentRecoveryRepository recovery,
            PlatformTransactionManager transactionManager, PaymentProvider psp,
            FxConversionService fxConversionService,
            @Value("${ATLAS_PUBLIC_BASE_URL:http://127.0.0.1:8080}") String publicBaseUrl,
            @Value("${atlas.payment.recovery.initial-delay:PT30S}") Duration recoveryInitialDelay,
            @Value("${atlas.payment.approval-ttl:PT30M}") Duration approvalTtl) {
        this(repository, refunds, webhookEvents, recovery, transactionManager, psp, fxConversionService,
                publicBaseUrl, recoveryInitialDelay, approvalTtl, UUID::randomUUID);
    }

    public PaymentServiceImpl(PaymentAttemptRepository repository, PaymentRefundRepository refunds,
            PaymentWebhookEventRepository webhookEvents, PaymentRecoveryRepository recovery,
            PlatformTransactionManager transactionManager, PaymentProvider psp,
            FxConversionService fxConversionService, String publicBaseUrl) {
        this(repository, refunds, webhookEvents, recovery, transactionManager, psp, fxConversionService,
                publicBaseUrl, Duration.ofSeconds(30), Duration.ofMinutes(30), UUID::randomUUID);
    }

    PaymentServiceImpl(PaymentAttemptRepository repository, PaymentRefundRepository refunds,
            PaymentWebhookEventRepository webhookEvents, PaymentRecoveryRepository recovery,
            PlatformTransactionManager transactionManager, PaymentProvider psp,
            FxConversionService fxConversionService, String publicBaseUrl, Duration recoveryInitialDelay,
            Duration approvalTtl, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.recovery = Objects.requireNonNull(recovery);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.psp = Objects.requireNonNull(psp);
        this.fxConversionService = Objects.requireNonNull(fxConversionService);
        String baseUrl = Objects.requireNonNull(publicBaseUrl).trim();
        if (baseUrl.isBlank()) throw new IllegalStateException("ATLAS_PUBLIC_BASE_URL is required");
        this.publicBaseUrl = baseUrl.replaceAll("/+$", "");
        this.ids = Objects.requireNonNull(ids);
        this.recoveryInitialDelay = Objects.requireNonNull(recoveryInitialDelay);
        if (this.recoveryInitialDelay.isNegative()) {
            throw new IllegalArgumentException("recoveryInitialDelay must not be negative");
        }
        this.approvalTtl = Objects.requireNonNull(approvalTtl);
        if (this.approvalTtl.isZero() || this.approvalTtl.isNegative()) {
            throw new IllegalArgumentException("approvalTtl must be positive");
        }
        this.refundWorkflow = new PaymentRefundWorkflow(ownerLocks, this.repository, refunds, this.recovery,
                this.transactions, this.psp, this.recoveryInitialDelay, this.ids);
        this.webhookProcessor = new PaymentWebhookProcessor(ownerLocks, this.repository, refunds, webhookEvents,
                this.recovery, this.transactions, this.psp);
    }
    @Override
    public PaymentResponse prepareInitial(UUID orderId, BigDecimal amount) {
        if (orderId == null) throw invalidRequest("orderId required");
        long amountWon = amountWon(amount);
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = preparePaymentAttempt(orderId, null, amountWon);
            verifyPreparedAmount(attempt, amountWon);
            return toResponse(attempt);
        });
    }

    @Override
    public PaymentResponse prepareCheckout(UUID checkoutId, BigDecimal amount) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        long amountWon = amountWon(amount);
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = preparePaymentAttempt(null, checkoutId, amountWon);
            verifyPreparedAmount(attempt, amountWon);
            return toResponse(attempt);
        });
    }

    @Override
    public PaymentResponse submitInitial(UUID orderId, BigDecimal amount) {
        if (orderId == null) throw invalidRequest("orderId required");
        long amountWon = amountWon(amount);
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = preparePaymentAttempt(orderId, null, amountWon);
            verifyPreparedAmount(attempt, amountWon);
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            return performPhase(attempt, "initial:" + attempt.getAttemptId());
        });
    }

    @Override
    public PaymentResponse submitCheckout(UUID checkoutId, BigDecimal amount) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        long amountWon = amountWon(amount);
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = preparePaymentAttempt(null, checkoutId, amountWon);
            verifyPreparedAmount(attempt, amountWon);
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            return performPhase(attempt, "initial:" + attempt.getAttemptId());
        });
    }

    private PaymentAttempt preparePaymentAttempt(UUID orderId, UUID checkoutId, long amountWon) {
        return Objects.requireNonNull(transactions.execute(status -> {
            if (orderId != null) repository.insertInitial(orderId, amountWon, ids.get());
            else repository.insertCheckoutInitial(checkoutId, amountWon, ids.get());
            PaymentAttempt attempt = orderId != null ? requireOrder(orderId) : requireCheckout(checkoutId);
            if (!attempt.getState().isTerminal()) {
                recovery.enqueueAttempt(attempt.getAttemptId(), recoveryInitialDelay);
            }
            return attempt;
        }));
    }

    private static void verifyPreparedAmount(PaymentAttempt attempt, long amountWon) {
        if (attempt.getTotalWon() == null || attempt.getTotalWon() != amountWon) {
            throw invalidRequest("PayPal amount does not match the stored payment attempt");
        }
    }

    @Override
    public PaymentResponse completeApproval(UUID orderId, String providerOrderId) {
        return completeApprovalForOwner(orderId, null, providerOrderId);
    }

    @Override
    public PaymentResponse completeCheckoutApproval(UUID checkoutId, String providerOrderId) {
        return completeApprovalForOwner(null, checkoutId, providerOrderId);
    }

    private PaymentResponse completeApprovalForOwner(UUID orderId, UUID checkoutId,
            String providerOrderId) {
        if ((orderId == null) == (checkoutId == null) || providerOrderId == null
                || providerOrderId.isBlank()) throw invalidRequest("A provider order ID is required");
        UUID ownerId = orderId == null ? checkoutId : orderId;
        return withOwnerLock(ownerId, () -> {
            PhaseStart start = transactions.execute(status -> {
                PaymentAttempt current = orderId == null
                        ? repository.findWithLockByCheckoutId(checkoutId)
                                .orElseThrow(() -> notFound("No payment attempt for checkout: " + checkoutId))
                        : repository.findWithLockByOrderId(orderId)
                                .orElseThrow(() -> notFound("No payment attempt for order: " + orderId));
                if (!Objects.equals(current.getProviderOrderId(), providerOrderId)) {
                    throw invalidRequest("PayPal approval does not match the payment attempt");
                }
                if (current.getPaymentPhase() != PaymentPhase.AUTHORIZATION
                        || current.getState() != PaymentState.AUTHORIZATION_PENDING) {
                    return new PhaseStart(current, null, PaymentPhase.AUTHORIZATION, null);
                }
                PaymentRequest request = providerRequest(current, PaymentPhase.AUTHORIZATION);
                repository.save(current);
                recovery.activateAttemptFromBuyer(current.getAttemptId(), recoveryInitialDelay);
                return new PhaseStart(current, request, PaymentPhase.AUTHORIZATION,
                        phaseRequestId(current, PaymentPhase.AUTHORIZATION));
            });
            if (start.request() == null) return toResponse(start.attempt());
            PaymentProviderResult result;
            try {
                result = psp.completeApproval(start.request(), providerOrderId);
            } catch (PaymentException exception) {
                if (exception.code() != PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE) throw exception;
                return toResponse(finalizePhase(start, null, PaymentProviderResult.Outcome.UNKNOWN));
            }
            return toResponse(finalizePhase(start, result, result.outcome()));
        });
    }
    @Override
    public PaymentResponse reconcile(UUID orderId, String idempotencyKey) {
        validateOwnerAndKey(orderId, idempotencyKey, "orderId");
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireOrder(orderId));
            if (attempt.getState().isTerminal()) return toResponse(attempt);
            return performPhase(attempt, idempotencyKey);
        });
    }

    @Override
    public PaymentResponse reconcileCheckout(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireCheckout(checkoutId));
            if (attempt.getState().isTerminal()) return toResponse(attempt);
            return performPhase(attempt, idempotencyKey);
        });
    }

    @Override
    public PaymentResponse submitCheckoutAuthorization(UUID checkoutId, BigDecimal amount) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        long amountWon = amountWon(amount);
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = preparePaymentAttempt(null, checkoutId, amountWon);
            verifyPreparedAmount(attempt, amountWon);
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            return performPhase(attempt, "initial:" + attempt.getAttemptId(), false);
        });
    }

    @Override
    public PaymentResponse reconcileCheckoutAuthorization(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireCheckout(checkoutId));
            if (attempt.getState().isTerminal()) return toResponse(attempt);
            return performPhase(attempt, idempotencyKey, false);
        });
    }

    @Override
    public PaymentResponse reconcileForCancellation(UUID orderId, String idempotencyKey) {
        validateOwnerAndKey(orderId, idempotencyKey, "orderId");
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireOrder(orderId));
            if (attempt.getState().isTerminal()) return toResponse(attempt);
            String phaseKey = attempt.getPaymentPhase() == PaymentPhase.VOID
                    ? idempotencyKey + ":void" : idempotencyKey + ":cancel-reconcile";
            return performPhase(attempt, phaseKey, false);
        });
    }

    @Override
    public PaymentResponse reconcileCheckoutForCancellation(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireCheckout(checkoutId));
            if (attempt.getState().isTerminal()) return toResponse(attempt);
            String phaseKey = attempt.getPaymentPhase() == PaymentPhase.VOID
                    ? idempotencyKey + ":void" : idempotencyKey + ":cancel-reconcile";
            return performPhase(attempt, phaseKey, false);
        });
    }

    @Override
    public PaymentResponse resolveExpiredApproval(UUID attemptId, String idempotencyKey) {
        if (attemptId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw invalidRequest("attemptId and idempotencyKey required");
        }
        PaymentAttempt loaded = Objects.requireNonNull(transactions.execute(status ->
                repository.findWithLockByAttemptId(attemptId)
                        .orElseThrow(() -> notFound("No payment attempt: " + attemptId))));
        UUID ownerId = loaded.getCheckoutId() == null ? loaded.getOrderId() : loaded.getCheckoutId();
        return withOwnerLock(ownerId, () -> {
            PaymentAttempt current = Objects.requireNonNull(transactions.execute(status ->
                    repository.findWithLockByAttemptId(attemptId)
                            .orElseThrow(() -> notFound("No payment attempt: " + attemptId))));
            // 이미 확정된 결과(capture/void/decline)는 뒤집지 않는다. owner 적용만 남았다.
            if (current.getState().isTerminal() || current.getProviderOrderId() == null
                    || current.getState() != PaymentState.AUTHORIZATION_PENDING) {
                return toResponse(current);
            }
            // 승인 화면 이탈 여부를 추정하지 않고 provider에 실제 상태를 확인한다. 승인 화면에서
            // 이탈했더라도 capture가 이미 확정됐을 수 있으므로, 조회 없이 void하면 자금 이동을
            // 놓친다.
            PaymentResponse reconciled = current.getCheckoutId() == null
                    ? reconcile(current.getOrderId(), idempotencyKey + ":get")
                    : reconcileCheckout(current.getCheckoutId(), idempotencyKey + ":get");
            if (reconciled.state().isTerminal() || !reconciled.authorizationActive()
                    || reconciled.state() != PaymentState.AUTHORIZATION_PENDING) {
                return reconciled;
            }
            // 아직 승인 전임이 provider 조회로 확인됐다. 이 시점에는 PayPal authorization이
            // 존재하지 않는다(승인 후 우리가 /authorize를 호출해야 생성된다). 따라서 취소할 자금이
            // 없고, order를 로컬에서 VOIDED로 닫으면 owner 적용이 재고·슬롯을 정리한다.
            // 승인 대기 flag를 함께 내려야 APPLY 단계로 넘어갈 수 있다.
            PaymentAttempt closed = Objects.requireNonNull(transactions.execute(status -> {
                PaymentAttempt latest = repository.findWithLockByAttemptId(attemptId)
                        .orElseThrow(() -> notFound("No payment attempt: " + attemptId));
                if (latest.getState().isTerminal()
                        || latest.getState() != PaymentState.AUTHORIZATION_PENDING) {
                    return latest;
                }
                latest.setApprovalExpiresAt(null);
                latest.setState(PaymentState.VOIDED);
                latest.setPaymentPhase(PaymentPhase.AUTHORIZATION);
                latest.setAuthorizationActive(false);
                repository.save(latest);
                recovery.markAttemptForApplication(attemptId);
                return latest;
            }));
            return toResponse(closed);
        });
    }

    @Override
    public PaymentResponse voidAuthorization(UUID orderId, String idempotencyKey) {
        validateOwnerAndKey(orderId, idempotencyKey, "orderId");
        return withOwnerLock(orderId, () -> voidAuthorizationForOwner(orderId, null, idempotencyKey));
    }

    @Override
    public PaymentResponse voidCheckoutAuthorization(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> voidAuthorizationForOwner(null, checkoutId, idempotencyKey));
    }

    private PaymentResponse voidAuthorizationForOwner(UUID orderId, UUID checkoutId, String idempotencyKey) {
        PaymentAttempt attempt = transactions.execute(status -> {
            PaymentAttempt current = orderId == null
                    ? repository.findWithLockByCheckoutId(checkoutId)
                            .orElseThrow(() -> notFound("No payment attempt for checkout: " + checkoutId))
                    : repository.findWithLockByOrderId(orderId)
                            .orElseThrow(() -> notFound("No payment attempt for order: " + orderId));
            if (!current.isAuthorizationActive() || current.getState() == PaymentState.VOIDED) {
                return current;
            }
            if (current.getState() == PaymentState.AUTHORIZED || current.getState() == PaymentState.DECLINED) {
                current.setState(PaymentState.VOID_PENDING);
                current.setPaymentPhase(PaymentPhase.VOID);
                current.setAuthorizationActive(true);
                repository.save(current);
            }
            return current;
        });
        if (attempt.getPaymentPhase() != PaymentPhase.VOID
                || (attempt.getState() != PaymentState.VOID_PENDING
                && attempt.getState() != PaymentState.UNKNOWN)) {
            return toResponse(attempt);
        }
        return performPhase(attempt, idempotencyKey);
    }
    @Override

    public PaymentResponse find(UUID orderId) {
        if (orderId == null) throw invalidRequest("orderId required");
        PaymentAttempt attempt = repository.findByOrderId(orderId).orElse(null);
        return attempt == null ? null : toResponse(attempt);
    }

    @Override

    public PaymentResponse findCheckout(UUID checkoutId) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        PaymentAttempt attempt = repository.findByCheckoutId(checkoutId).orElse(null);
        return attempt == null ? null : toResponse(attempt);
    }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prohibitNewCheckoutCapture(UUID checkoutId) {
        if (checkoutId == null) throw invalidRequest("checkoutId is required");
        repository.findWithLockByCheckoutId(checkoutId).ifPresent(this::prohibitNewCapture);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prohibitNewOrderCapture(UUID orderId) {
        if (orderId == null) throw invalidRequest("orderId is required");
        repository.findWithLockByOrderId(orderId).ifPresent(this::prohibitNewCapture);
    }

    private void prohibitNewCapture(PaymentAttempt attempt) {
        attempt.prohibitNewCapture();
        repository.save(attempt);
    }


    @Override
    public boolean abandonUnstartedCheckout(UUID checkoutId) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            PaymentAttempt attempt = repository.findWithLockByCheckoutId(checkoutId).orElse(null);
            if (attempt == null) return true;
            if (attempt.getState() != PaymentState.NOT_STARTED) return false;
            // provider 에 아무것도 보내지 않았으므로 승인도 없다. 되돌릴 PSP 작업 없이 종결한다.
            attempt.setState(PaymentState.VOIDED);
            attempt.setAuthorizationActive(false);
            repository.save(attempt);
            return true;
        }));
    }

    @Override
    public boolean matchesCheckoutCancelState(UUID checkoutId, String state) {
        if (checkoutId == null || state == null || state.isBlank()) return false;
        return repository.findByCheckoutId(checkoutId)
                .map(attempt -> state.equals(attempt.getCreateOrderRequestId()))
                .orElse(false);
    }

    @Override
    public PaymentResponse findByAttemptId(UUID attemptId) {
        if (attemptId == null) throw invalidRequest("attemptId required");
        PaymentAttempt attempt = repository.findById(attemptId).orElse(null);
        return attempt == null ? null : toResponse(attempt);
    }

    @Override
    public void markOwnerApplied(UUID attemptId) {
        if (attemptId == null) throw invalidRequest("attemptId required");
        transactions.execute(status -> {
            PaymentAttempt attempt = repository.findWithLockByAttemptId(attemptId)
                    .orElseThrow(() -> notFound("No payment attempt: " + attemptId));
            if (attempt.getState().isTerminal() && !attempt.isAuthorizationActive()) {
                recovery.markAttemptApplied(attemptId);
            }
            return null;
        });
    }


    private void ensureFxSnapshot(UUID attemptId) {
        if (!psp.requiresFxSnapshot()) return;
        PaymentAttempt attempt = Objects.requireNonNull(transactions.execute(status ->
                repository.findById(attemptId).orElseThrow(() -> notFound("No payment attempt: " + attemptId))));
        if (attempt.hasFxSnapshot()) return;
        if (attempt.getPaymentPhase() != PaymentPhase.AUTHORIZATION || attempt.getProviderOrderId() != null
                || attempt.getTotalWon() == null || attempt.getTotalWon() <= 0) {
            throw invalidRequest("A saved FX snapshot is required for this PayPal operation");
        }

        FxConversionService.Conversion conversion;
        try {
            conversion = fxConversionService.convertKrwToUsdCents(attempt.getTotalWon());
        } catch (IllegalArgumentException | ArithmeticException invalidAmount) {
            throw invalidRequest("PayPal 결제 금액이 허용 범위를 벗어났습니다");
        }
        long providerAmount = conversion.usdCents();
        if (providerAmount <= 0) throw invalidRequest("Payment amount is below the PayPal minimum");
        transactions.execute(status -> {
            PaymentAttempt current = repository.findWithLockByAttemptId(attemptId)
                    .orElseThrow(() -> notFound("No payment attempt: " + attemptId));
            if (!current.hasFxSnapshot()) {
                if (!Objects.equals(current.getTotalWon(), attempt.getTotalWon())) {
                    throw invalidRequest("Payment amount changed while preparing the FX snapshot");
                }
                current.setFxSnapshot(conversion.quote().wonPerUsd(), conversion.quote().source(),
                        conversion.quote().asOfDate(), providerAmount, "USD");
                repository.save(current);
            }
            return null;
        });
    }


    @Override
    public void markRefundApplied(UUID refundOperationId) {
        refundWorkflow.markRefundApplied(refundOperationId);
    }

    @Override
    public PaymentRefundResponse refundOrder(UUID orderId, String idempotencyKey, long amountWon) {
        return refundWorkflow.refundOrder(orderId, idempotencyKey, amountWon);
    }

    @Override
    public PaymentRefundResponse refundCheckout(UUID checkoutId, String idempotencyKey, long amountWon) {
        return refundWorkflow.refundCheckout(checkoutId, idempotencyKey, amountWon);
    }

    @Override
    public PaymentRefundResponse prepareRefundOrder(UUID orderId, String idempotencyKey, long amountWon) {
        return refundWorkflow.prepareRefundOrder(orderId, idempotencyKey, amountWon);
    }

    @Override
    public PaymentRefundResponse prepareRefundCheckout(UUID checkoutId, String idempotencyKey, long amountWon) {
        return refundWorkflow.prepareRefundCheckout(checkoutId, idempotencyKey, amountWon);
    }

    @Override
    public PaymentRefundResponse retryRefundOperation(UUID refundOperationId) {
        return refundWorkflow.retryRefundOperation(refundOperationId);
    }

    @Override
    public PaymentRefundResponse findRefundOperation(UUID refundOperationId) {
        return refundWorkflow.findRefundOperation(refundOperationId);
    }

    @Override
    public PaymentWebhookEvent verifyPayPalWebhook(PaymentWebhookSignature signature, String rawBody) {
        return webhookProcessor.verifyPayPalWebhook(signature, rawBody);
    }

    @Override
    public <T> T withPayPalWebhookOwnerLock(PaymentWebhookEvent event, Supplier<T> action) {
        return webhookProcessor.withPayPalWebhookOwnerLock(event, action);
    }

    @Override
    public PaymentWebhookResult processVerifiedPayPalWebhook(PaymentWebhookEvent event) {
        return webhookProcessor.processVerifiedPayPalWebhook(event);
    }

    private record PhaseStart(PaymentAttempt attempt, PaymentRequest request,
            PaymentPhase phase, String requestId) {}

    private PhaseStart reservePhase(UUID attemptId, PaymentPhase phase, boolean continueCapture) {
        return transactions.execute(status -> {
            PaymentAttempt current = repository.findWithLockByAttemptId(attemptId)
                    .orElseThrow(() -> notFound("No payment attempt: " + attemptId));
            // AUTHORIZED만 아직 캡처를 예약하지 않은 상태다. PENDING과 UNKNOWN은
            // provider에 전달됐을 수 있으므로 계속 복구해야 한다.
            if (phase == PaymentPhase.CAPTURE && current.getState() == PaymentState.AUTHORIZED
                    && current.isCaptureProhibited()) {
                return new PhaseStart(current, null, phase, null);
            }
            if (current.getPaymentPhase() != phase || current.getState().isTerminal()
                    || (!continueCapture && phase == PaymentPhase.CAPTURE
                    && current.getState() == PaymentState.AUTHORIZED)
                    || !canExecutePhase(current, phase)) {
                return new PhaseStart(current, null, phase, null);
            }
            switch (phase) {
                case AUTHORIZATION -> {
                    if (current.getState() == PaymentState.NOT_STARTED
                            || current.getState() == PaymentState.AUTHORIZATION_PENDING) {
                        current.setState(PaymentState.AUTHORIZATION_PENDING);
                        // 승인 화면 이탈 시에도 deadline이 남도록 대기 시작 시점에 기록한다.
                        // 구매자가 callback을 보내지 않아도 이 시각 이후 worker가 확정한다.
                        if (current.getApprovalExpiresAt() == null) {
                            current.setApprovalExpiresAt(Instant.now().plus(approvalTtl));
                        }
                    }
                    current.setAuthorizationActive(true);
                }
                case CAPTURE -> {
                    current.setState(PaymentState.CAPTURE_PENDING);
                    current.setAuthorizationActive(true);
                }
                case VOID -> {
                    current.setState(PaymentState.VOID_PENDING);
                    current.setAuthorizationActive(true);
                }
            }
            PaymentRequest request = providerRequest(current, phase);
            String requestId = phaseRequestId(current, phase);
            repository.save(current);
                recovery.resumeAttempt(current.getAttemptId(), recoveryInitialDelay);
            return new PhaseStart(current, request, phase, requestId);
        });
    }

    private static boolean canExecutePhase(PaymentAttempt attempt, PaymentPhase phase) {
        if (attempt.getPaymentPhase() != phase) return false;
        return switch (phase) {
            case AUTHORIZATION -> attempt.getState() == PaymentState.NOT_STARTED
                    || attempt.getState() == PaymentState.AUTHORIZATION_PENDING
                    || attempt.getState() == PaymentState.UNKNOWN;
            case CAPTURE -> attempt.getState() == PaymentState.AUTHORIZED
                    || attempt.getState() == PaymentState.CAPTURE_PENDING
                    || attempt.getState() == PaymentState.UNKNOWN;
            case VOID -> attempt.getState() == PaymentState.VOID_PENDING
                    || attempt.getState() == PaymentState.UNKNOWN;
        };
    }

    private PaymentAttempt finalizePhase(PhaseStart start, PaymentProviderResult result,
            PaymentProviderResult.Outcome outcome) {
        return transactions.execute(status -> {
            PaymentAttempt current = repository.findWithLockByAttemptId(start.attempt().getAttemptId())
                    .orElseThrow(() -> notFound("No payment attempt: " + start.attempt().getAttemptId()));
            if (current.getState().isTerminal() || current.getPaymentPhase() != start.phase()
                    || !Objects.equals(start.requestId(), phaseRequestId(current, start.phase()))) {
                return current;
            }
            if (result != null) applyProviderResult(current, result);
            switch (start.phase()) {
                case AUTHORIZATION -> authorizationResult(current, outcome);
                case CAPTURE -> captureResult(current, outcome);
                case VOID -> voidResult(current, outcome);
            }
            repository.save(current);
            if (current.getState().isTerminal() && !current.isAuthorizationActive()) {
                recovery.markAttemptForApplication(current.getAttemptId());
            }
            return current;
        });
    }

    private static String phaseRequestId(PaymentAttempt attempt, PaymentPhase phase) {
        return switch (phase) {
            case AUTHORIZATION -> attempt.getProviderOrderId() == null
                    ? attempt.getCreateOrderRequestId() : attempt.getAuthorizationRequestId();
            case CAPTURE -> attempt.getCaptureRequestId();
            case VOID -> attempt.getVoidRequestId();
        };
    }
    private PaymentResponse performPhase(PaymentAttempt attempt, String idempotencyKey) {
        return performPhase(attempt, idempotencyKey, true);
    }
    private PaymentResponse performPhase(PaymentAttempt attempt, String idempotencyKey, boolean continueCapture) {
        if (attempt.getPaymentPhase() == PaymentPhase.AUTHORIZATION && attempt.getProviderOrderId() == null) {
            ensureFxSnapshot(attempt.getAttemptId());
        }
        PhaseStart start = reservePhase(attempt.getAttemptId(), attempt.getPaymentPhase(), continueCapture);
        if (start.request() == null) return toResponse(start.attempt());
        boolean reconcileUnknownCapture = attempt.getPaymentPhase() == PaymentPhase.CAPTURE
                && attempt.getState() == PaymentState.UNKNOWN && psp.requiresFxSnapshot();
        PaymentProviderResult result;
        try {
            if (reconcileUnknownCapture) {
                result = psp.completeApproval(start.request(), start.request().providerOrderId());
                if (result.outcome() == PaymentProviderResult.Outcome.AUTHORIZED) {
                    result = psp.perform(start.request());
                }
            } else {
                result = psp.perform(start.request());
            }
        } catch (PaymentException exception) {
            if (exception.code() != PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE) throw exception;
            return toResponse(finalizePhase(start, null, PaymentProviderResult.Outcome.UNKNOWN));
        }
        PaymentAttempt current = finalizePhase(start, result, result.outcome());
        if (continueCapture && current.getPaymentPhase() == PaymentPhase.CAPTURE
                && current.getState() == PaymentState.AUTHORIZED) {
            return performPhase(current, idempotencyKey, true);
        }
        return toResponse(current);
    }
    private void authorizationResult(PaymentAttempt attempt, PaymentProviderResult.Outcome result) {
        switch (result) {
            case AUTHORIZED -> {
                attempt.setState(PaymentState.AUTHORIZED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            case DECLINED -> {
                attempt.setState(PaymentState.DECLINED);
                attempt.setPaymentPhase(PaymentPhase.AUTHORIZATION);
                attempt.setAuthorizationActive(false);
            }
            case CAPTURED -> {
                attempt.setState(PaymentState.CAPTURED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(false);
            }
            case VOIDED -> {
                attempt.setState(PaymentState.VOIDED);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(false);
            }
            case PENDING -> {
                attempt.setState(PaymentState.AUTHORIZATION_PENDING);
                attempt.setPaymentPhase(PaymentPhase.AUTHORIZATION);
                attempt.setAuthorizationActive(true);
            }
            case UNKNOWN -> {
                attempt.setState(PaymentState.UNKNOWN);
                attempt.setPaymentPhase(PaymentPhase.AUTHORIZATION);
                attempt.setAuthorizationActive(true);
            }
            default -> throw invalidResult(PaymentPhase.AUTHORIZATION, result);
        }
    }

    private void captureResult(PaymentAttempt attempt, PaymentProviderResult.Outcome result) {
        switch (result) {
            case CAPTURED -> {
                attempt.setState(PaymentState.CAPTURED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(false);
            }
            case DECLINED -> {
                attempt.setState(PaymentState.DECLINED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            case PENDING -> {
                attempt.setState(PaymentState.CAPTURE_PENDING);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            case UNKNOWN -> {
                attempt.setState(PaymentState.UNKNOWN);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            default -> throw invalidResult(PaymentPhase.CAPTURE, result);
        }
    }

    private void voidResult(PaymentAttempt attempt, PaymentProviderResult.Outcome result) {
        switch (result) {
            case VOIDED -> {
                attempt.setState(PaymentState.VOIDED);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(false);
            }
            case PENDING -> {
                attempt.setState(PaymentState.VOID_PENDING);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(true);
            }
            case UNKNOWN -> {
                attempt.setState(PaymentState.UNKNOWN);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(true);
            }
            default -> throw invalidResult(PaymentPhase.VOID, result);
        }
    }

    private PaymentRequest providerRequest(PaymentAttempt attempt, PaymentPhase phase) {
        if (psp.requiresFxSnapshot() && !attempt.hasFxSnapshot()) {
            throw invalidRequest("A saved FX snapshot is required for this PayPal operation");
        }
        Long amount = psp.requiresFxSnapshot() ? attempt.getProviderAmountMinor() : attempt.getTotalWon();
        String providerCurrency = psp.requiresFxSnapshot() ? attempt.getProviderCurrency() : psp.providerCurrency();
        if (amount == null || amount <= 0 || providerCurrency == null || providerCurrency.isBlank()) {
            throw invalidRequest("저장된 결제 금액이 없어 결제를 진행할 수 없습니다");
        }
        String createId = stableRequestId(attempt, "create-order");
        String authorizeId = stableRequestId(attempt, "authorize");
        String captureId = stableRequestId(attempt, "capture");
        String voidId = stableRequestId(attempt, "void");
        attempt.setCreateOrderRequestId(createId);
        attempt.setAuthorizationRequestId(authorizeId);
        attempt.setCaptureRequestId(captureId);
        attempt.setVoidRequestId(voidId);
        UUID owner = attempt.getCheckoutId() == null ? attempt.getOrderId() : attempt.getCheckoutId();
        String ownerText = owner.toString();
        String base = publicBaseUrl;
        String returnPath = attempt.getCheckoutId() == null ? "/api/orders/" + ownerText + "/payment/return"
                : "/api/checkouts/" + ownerText + "/payment/return";
        String cancelPath = attempt.getCheckoutId() == null ? "/api/orders/" + ownerText + "/payment/cancel"
                : "/api/checkouts/" + ownerText + "/payment/cancel";
        PaymentOperation operation = switch (phase) {
            case AUTHORIZATION -> PaymentOperation.AUTHORIZATION;
            case CAPTURE -> PaymentOperation.CAPTURE;
            case VOID -> PaymentOperation.VOID;
        };
        String operationId = switch (phase) {
            case AUTHORIZATION -> attempt.getProviderOrderId() == null ? createId : authorizeId;
            case CAPTURE -> captureId;
            case VOID -> voidId;
        };
        String cancelUrl = base + cancelPath;
        if (attempt.getCheckoutId() != null) {
            cancelUrl += (cancelUrl.contains("?") ? "&" : "?") + "state=" + createId;
        }
        return new PaymentRequest(operation, owner, attempt.getAttemptId(), amount, providerCurrency,
                attempt.getProviderOrderId(), attempt.getProviderAuthorizationId(),
                operationId, authorizeId, captureId, voidId, base + returnPath, cancelUrl);
    }

    private static String stableRequestId(PaymentAttempt attempt, String operation) {
        return UUID.nameUUIDFromBytes((attempt.getAttemptId() + ":" + operation)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void applyProviderResult(PaymentAttempt attempt, PaymentProviderResult result) {
        if (result.orderId() != null) attempt.setProviderOrderId(result.orderId());
        if (result.authorizationId() != null) attempt.setProviderAuthorizationId(result.authorizationId());
        if (result.captureId() != null) attempt.setProviderCaptureId(result.captureId());
        if (result.approvalUrl() != null) attempt.setApprovalUrl(result.approvalUrl());
    }

    private static long amountWon(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw invalidRequest("A positive payment amount is required");
        }
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException invalid) {
            throw invalidRequest("Payment amount must be a whole number of won");
        }
    }

    private <T> T withOwnerLock(UUID ownerId, Supplier<T> action) {
        return ownerLocks.withLock(ownerId, action);
    }

    private PaymentAttempt requireOrder(UUID orderId) {
        return PaymentSupport.requireOrder(repository, orderId);
    }

    private PaymentAttempt requireCheckout(UUID checkoutId) {
        return PaymentSupport.requireCheckout(repository, checkoutId);
    }

    private static PaymentResponse toResponse(PaymentAttempt attempt) {
        return new PaymentResponse(attempt.getOrderId(), attempt.getCheckoutId(), attempt.getAttemptId(),
                attempt.getState(), attempt.isAuthorizationActive(), attempt.getProviderOrderId(),
                attempt.getProviderCaptureId(), attempt.getApprovalUrl(), attempt.getApprovalExpiresAt());
    }


    private static IllegalStateException invalidResult(PaymentPhase phase, PaymentProviderResult.Outcome result) {
        return new IllegalStateException("Provider result " + result + " is invalid for " + phase);
    }
}