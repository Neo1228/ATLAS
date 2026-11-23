package com.atlas.payment.service;

import com.atlas.payment.dto.response.PayPalReportMatchCandidateResponse;
import java.util.Collection;
import java.util.List;

/** PayPal report 대사를 위한 조회 전용 provider 거래 사실. */
public interface PayPalReportMatchQuery {
    List<PayPalReportMatchCandidateResponse> findByRecordIds(Collection<String> recordIds);
}
