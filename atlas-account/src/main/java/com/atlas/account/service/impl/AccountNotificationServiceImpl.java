package com.atlas.account.service.impl;

import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.model.AccountActionTokenPurpose;
import com.atlas.account.model.AccountNotificationOutcome;
import com.atlas.account.model.AccountNotificationStatus;
import com.atlas.account.persistence.projection.ClaimedAccountNotification;
import com.atlas.account.repository.AccountNotificationRepository;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountEmailSender;
import com.atlas.account.service.AccountNotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 outbox 행을 하나씩 청구해 토큰을 발급하고 SMTP로 보낸다(ADR-0102, EXP-113).
 *
 * <p>순서: (1) 한 transaction에서 임대를 잡고 계정 상태를 확인한 뒤 토큰을 발급해 커밋, (2) transaction 밖에서 전송,
 * (3) 성공이면 행을 지우고 실패면 지수 backoff로 재예약하거나 최대 횟수 뒤 FAILED로 남긴다. SMTP 호출은 DB 연결을
 * 쥐지 않는다. (1)과 (3) 사이에 프로세스가 죽으면 임대 만료 뒤 다른 worker가 다시 잡아 새 토큰으로 보낸다.
 * 한 번에 한 행만 청구하므로 임대는 메일 한 통의 최대 전송 시간(SMTP timeout 합)만 덮으면 된다.
 */
@Service
public class AccountNotificationServiceImpl implements AccountNotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountNotificationServiceImpl.class);
    private final AccountNotificationRepository notifications;
    private final AccountEmailSender emailSender;
    private final ActionTokenIssuer actionTokens;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;

    public AccountNotificationServiceImpl(AccountNotificationRepository notifications, AccountRepository accounts,
            AccountEmailSender emailSender, PlatformTransactionManager transactionManager, Clock clock,
            @Value("${atlas.account.notification.lease-duration:PT2M}") Duration leaseDuration,
            @Value("${atlas.account.notification.retry.initial-backoff:PT5S}") Duration initialBackoff,
            @Value("${atlas.account.notification.retry.max-backoff:PT5M}") Duration maxBackoff,
            @Value("${atlas.account.notification.retry.max-attempts:10}") int maxAttempts) {
        if (leaseDuration.isNegative() || leaseDuration.isZero() || initialBackoff.isNegative()
                || maxBackoff.compareTo(initialBackoff) < 0 || maxAttempts < 1) {
            throw new IllegalArgumentException("account notification retry settings are invalid");
        }
        this.notifications = Objects.requireNonNull(notifications);
        this.emailSender = Objects.requireNonNull(emailSender);
        this.actionTokens = new ActionTokenIssuer(accounts, clock);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
        this.leaseDuration = leaseDuration;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public List<AccountResponse.NotificationDelivery> deliverDue(int limit) {
        List<AccountResponse.NotificationDelivery> deliveries = new ArrayList<>();
        for (int processed = 0; processed < limit; processed++) {
            Claim claim = transactions.execute(status -> claimAndIssue());
            if (claim == null) break;
            AccountNotificationOutcome outcome = send(claim);
            deliveries.add(new AccountResponse.NotificationDelivery(outcome.name().toLowerCase(Locale.ROOT),
                    outcome == AccountNotificationOutcome.SENT,
                    Duration.between(claim.row().requestedAt(), clock.instant())));
        }
        return deliveries;
    }

    @Override
    public AccountResponse.NotificationBacklog backlog() {
        Instant now = clock.instant();
        Duration oldest = notifications.oldestPendingRequestedAt()
                .map(requestedAt -> Duration.between(requestedAt, now))
                .filter(age -> !age.isNegative())
                .orElse(Duration.ZERO);
        return new AccountResponse.NotificationBacklog(notifications.countByStatus(AccountNotificationStatus.PENDING),
                notifications.countByStatus(AccountNotificationStatus.FAILED), oldest);
    }

    private Claim claimAndIssue() {
        Instant now = clock.instant();
        ClaimedAccountNotification row = notifications.claimNext(UUID.randomUUID(), now, now.plus(leaseDuration))
                .orElse(null);
        if (row == null) return null;
        if (!stillWanted(row)) return new Claim(row, null);
        return new Claim(row, actionTokens.issue(row.accountId(), row.purpose()));
    }

    /** 요청 뒤 계정이 비활성이 됐거나 확인 상태가 바뀌어 메일이 더는 의미 없으면 보내지 않는다. */
    private static boolean stillWanted(ClaimedAccountNotification row) {
        if (!row.active()) return false;
        return row.purpose() == AccountActionTokenPurpose.PASSWORD_RESET ? row.emailVerified() : !row.emailVerified();
    }

    private AccountNotificationOutcome send(Claim claim) {
        ClaimedAccountNotification row = claim.row();
        if (claim.token() == null) {
            return notifications.complete(row.accountId(), row.purpose(), row.leaseOwner())
                    ? AccountNotificationOutcome.SKIPPED : AccountNotificationOutcome.STALE_CLAIM;
        }
        try {
            ActionTokenIssuer.send(emailSender, row.purpose(), row.email(), claim.token());
        } catch (RuntimeException failure) {
            return recordFailure(row, failure);
        }
        return notifications.complete(row.accountId(), row.purpose(), row.leaseOwner())
                ? AccountNotificationOutcome.SENT : AccountNotificationOutcome.STALE_CLAIM;
    }

    private AccountNotificationOutcome recordFailure(ClaimedAccountNotification row, RuntimeException failure) {
        // 메시지에는 수신 주소가 섞일 수 있어 예외 종류만 남긴다.
        String failureType = failure.getClass().getSimpleName();
        LOGGER.warn("Account notification delivery failed; purpose={}, attempt={}, failureType={}",
                row.purpose(), row.attempts(), failureType);
        if (row.attempts() >= maxAttempts) {
            return notifications.markFailed(row.accountId(), row.purpose(), row.leaseOwner(), failureType)
                    ? AccountNotificationOutcome.FAILED : AccountNotificationOutcome.STALE_CLAIM;
        }
        Instant retryAt = clock.instant().plus(backoff(row.attempts()));
        return notifications.reschedule(row.accountId(), row.purpose(), row.leaseOwner(), retryAt, failureType)
                ? AccountNotificationOutcome.RETRY_SCHEDULED : AccountNotificationOutcome.STALE_CLAIM;
    }

    /** 1회 실패 뒤 initial, 이후 두 배씩, max에서 멈춘다. */
    Duration backoff(int attempts) {
        Duration delay = initialBackoff;
        for (int attempt = 1; attempt < attempts && delay.compareTo(maxBackoff) < 0; attempt++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    /** 청구한 행과, 보낼 경우 이번에 발급한 평문 토큰(메모리에만 있다). */
    private record Claim(ClaimedAccountNotification row, String token) {
        @Override
        public String toString() {
            return "Claim[accountId=" + row.accountId() + ", purpose=" + row.purpose() + ", token=[REDACTED]]";
        }
    }
}
