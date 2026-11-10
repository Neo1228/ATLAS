package com.atlas.application.service;

import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.service.PaymentService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PayPalWebhookService {
    private final PaymentService payments;
    private final OrderService orders;
    private final CheckoutPaymentService checkoutPayments;
    private final OrderRefundService refunds;
    private final TransactionTemplate transactions;

    public PayPalWebhookService(PlatformTransactionManager transactionManager, PaymentService payments,
            OrderService orders, CheckoutPaymentService checkoutPayments, OrderRefundService refunds) {
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.payments = Objects.requireNonNull(payments);
        this.orders = Objects.requireNonNull(orders);
        this.checkoutPayments = Objects.requireNonNull(checkoutPayments);
        this.refunds = Objects.requireNonNull(refunds);
    }

    public void handle(PaymentWebhookSignature signature, String rawBody) {
        PaymentWebhookEvent event = payments.verifyPayPalWebhook(signature, rawBody);
        if (event == null) throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                "PayPal webhook verification returned no event");

        transactions.executeWithoutResult(status -> {
            PaymentWebhookResult result = payments.processVerifiedPayPalWebhook(event);
            if (result.duplicate()) return;
            if (!result.accepted()) {
                throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                        "PayPal webhook could not be correlated yet; retry delivery");
            }

            if (result.captureConfirmed() && result.paymentAttemptId() != null) {
                PaymentResponse payment = payments.findByAttemptId(result.paymentAttemptId());
                if (payment == null) {
                    throw new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND,
                            "Payment attempt for PayPal webhook was not found");
                }
                if (payment.checkoutId() != null) {
                    checkoutPayments.reconcile(payment.checkoutId(), event.eventId());
                } else if (payment.orderId() != null) {
                    orders.reconcilePayment(payment.orderId(), event.eventId());
                }
            }

            if (result.refundOperationId() != null) {
                refunds.reconcilePayPalRefund(result.refundOperationId());
            }
        });
    }
}
