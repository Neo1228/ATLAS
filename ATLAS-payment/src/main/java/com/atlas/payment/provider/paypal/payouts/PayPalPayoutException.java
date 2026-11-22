package com.atlas.payment.provider.paypal.payouts;

public final class PayPalPayoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Classification { RETRYABLE, DEFINITIVE_NO_SEND, DUPLICATE_BATCH, UNKNOWN }

    private final Classification classification;
    private final String originalBatchId;
    private final String senderBatchId;

    public PayPalPayoutException(String message, Throwable cause, Classification classification,
            String senderBatchId, String originalBatchId) {
        super(message, cause);
        this.classification = classification;
        this.senderBatchId = senderBatchId;
        this.originalBatchId = originalBatchId;
    }

    public Classification getClassification() { return classification; }
    public String getOriginalBatchId() { return originalBatchId; }
    public String getSenderBatchId() { return senderBatchId; }
}
