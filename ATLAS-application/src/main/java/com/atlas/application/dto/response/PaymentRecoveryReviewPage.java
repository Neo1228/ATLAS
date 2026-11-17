package com.atlas.application.dto.response;

import com.atlas.payment.dto.response.PaymentRecoveryReviewItem;
import java.util.List;

public record PaymentRecoveryReviewPage(
        List<PaymentRecoveryReviewItem> items,
        int limit,
        int offset,
        long total) {
}
