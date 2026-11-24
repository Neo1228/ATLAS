package com.atlas.payment.service.impl;

import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.JdkIdGenerator;

/** 단위 테스트에서 Spring 없이 PaymentServiceImpl과 그 협력자를 같은 방식으로 조립한다. */
final class PaymentServiceFactory {
    private static final Duration RECOVERY_INITIAL_DELAY = Duration.ofSeconds(30);
    private static final Duration APPROVAL_TTL = Duration.ofMinutes(30);

    private PaymentServiceFactory() {
    }

    static PaymentServiceImpl create(PaymentAttemptRepository attempts, PaymentRefundRepository refunds,
            PaymentWebhookEventRepository webhookEvents, PaymentRecoveryRepository recovery,
            PlatformTransactionManager transactionManager, PaymentProvider psp,
            FxConversionService fxConversionService, String publicBaseUrl) {
        OwnerLockRegistry locks = new OwnerLockRegistry();
        JdkIdGenerator ids = new JdkIdGenerator();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        PaymentRefundWorkflow refundWorkflow = new PaymentRefundWorkflow(locks, attempts, refunds, recovery,
                transactions, psp, RECOVERY_INITIAL_DELAY, ids, Clock.systemUTC());
        PaymentWebhookProcessor webhookProcessor = new PaymentWebhookProcessor(locks, attempts, refunds,
                webhookEvents, recovery, transactions, psp, Clock.systemUTC());
        return new PaymentServiceImpl(attempts, recovery, transactionManager, psp, fxConversionService, locks,
                refundWorkflow, webhookProcessor, ids, Clock.systemUTC(), publicBaseUrl, RECOVERY_INITIAL_DELAY, APPROVAL_TTL);
    }
}
