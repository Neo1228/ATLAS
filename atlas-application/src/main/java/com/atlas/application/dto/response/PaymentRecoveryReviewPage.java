package com.atlas.application.dto.response;

import com.atlas.payment.dto.response.PaymentRecoveryReviewItemResponse;
import java.util.List;

public record PaymentRecoveryReviewPage(
        List<PaymentRecoveryReviewItemResponse> items,
        int limit,
        int offset,
        long total) {
}
