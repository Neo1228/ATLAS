package com.atlas.payment.service;

import java.util.Collection;
import java.util.List;

/** Read-only provider transaction facts used to reconcile PayPal reports. */
public interface PayPalReportMatchQuery {
    List<Candidate> findByRecordIds(Collection<String> recordIds);

    enum Kind {
        CAPTURE,
        REFUND
    }

    record Candidate(String recordId, Kind kind, boolean confirmed, String captureId,
            Long providerAmountMinor, String providerCurrency) {
    }
}
