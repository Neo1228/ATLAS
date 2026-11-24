package com.atlas.payment.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * PayPal Payouts v1 batch 조회·생성 응답.
 *
 * <p>{@code rawBatchStatus}와 {@code rawTransactionStatus}는 PayPal이 보낸 원문이다. 지급 증거
 * ({@code provider_batch_status}, {@code provider_item_status})는 이 원문으로 남기고, 분기는
 * {@link #batchStatus()}와 {@link Item#transactionStatus()}로 한다.
 */
public record PayPalPayoutBatchResponse(
        String batchId,
        String senderBatchId,
        String rawBatchStatus,
        BigDecimal amount,
        String currency,
        BigDecimal fees,
        String feeCurrency,
        List<Item> items) {
    public PayPalPayoutBatchResponse { items = items == null ? List.of() : List.copyOf(items); }

    public BatchStatus batchStatus() { return BatchStatus.from(rawBatchStatus); }

    public record Item(
            String itemId,
            String senderItemId,
            String rawTransactionStatus,
            BigDecimal amount,
            String currency,
            BigDecimal fee,
            String feeCurrency,
            BigDecimal conversionFromAmount,
            String conversionFromCurrency,
            BigDecimal conversionToAmount,
            String conversionToCurrency,
            BigDecimal conversionRate,
            boolean currencyConversionPresent) {

        public TransactionStatus transactionStatus() { return TransactionStatus.from(rawTransactionStatus); }
    }

    /**
     * {@code batch_header.batch_status}. 공식 값은
     * <a href="https://developer.paypal.com/docs/api/payments.payouts-batch/v1/">Payouts v1</a>의
     * {@code batch_enum}(PayPal OpenAPI {@code payments_payouts_batch_v1.json}): DENIED, PENDING, PROCESSING,
     * SUCCESS, CANCELED. {@link #FAILED}와 {@link #CANCELLED}는 공식 목록에 없지만 기존 분기가 종료 실패로
     * 다루던 값이라 그대로 둔다. 대소문자는 구분하지 않는다.
     */
    public enum BatchStatus {
        DENIED, PENDING, PROCESSING, SUCCESS, CANCELED, FAILED, CANCELLED, UNKNOWN;

        static BatchStatus from(String value) {
            if (value == null) return UNKNOWN;
            String normalized = value.toUpperCase(Locale.ROOT);
            for (BatchStatus status : values()) {
                if (status.name().equals(normalized)) return status;
            }
            return UNKNOWN;
        }
    }

    /**
     * {@code items[].transaction_status}. 공식 값은
     * <a href="https://developer.paypal.com/docs/api/payments.payouts-batch/v1/">Payouts v1</a>의
     * {@code transaction_enum}(PayPal OpenAPI {@code payments_payouts_batch_v1.json}): SUCCESS, FAILED, PENDING,
     * UNCLAIMED, RETURNED, ONHOLD, BLOCKED, REFUNDED, REVERSED. {@link #PROCESSING}과 {@link #DENIED}는 공식
     * 목록에 없지만 기존 분기가 각각 대기와 실패로 다루던 값이라 그대로 둔다. 대소문자는 구분하지 않는다.
     */
    public enum TransactionStatus {
        SUCCESS, FAILED, PENDING, UNCLAIMED, RETURNED, ONHOLD, BLOCKED, REFUNDED, REVERSED, PROCESSING, DENIED,
        UNKNOWN;

        static TransactionStatus from(String value) {
            if (value == null) return UNKNOWN;
            String normalized = value.toUpperCase(Locale.ROOT);
            for (TransactionStatus status : values()) {
                if (status.name().equals(normalized)) return status;
            }
            return UNKNOWN;
        }
    }
}
