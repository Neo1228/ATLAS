package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.PayPalReportMatchCandidateResponse;
import com.atlas.payment.dto.response.PayPalReportMatchKind;
import com.atlas.payment.persistence.projection.PayPalReportMatchFacts;
import com.atlas.payment.repository.PayPalReportMatchRepository;
import com.atlas.payment.service.PayPalReportMatchQuery;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayPalReportMatchQueryImpl implements PayPalReportMatchQuery {
    private final PayPalReportMatchRepository repository;

    public PayPalReportMatchQueryImpl(PayPalReportMatchRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayPalReportMatchCandidateResponse> findByRecordIds(Collection<String> recordIds) {
        return repository.findByRecordIds(recordIds).stream()
                .map(PayPalReportMatchQueryImpl::toCandidate)
                .toList();
    }

    private static PayPalReportMatchCandidateResponse toCandidate(PayPalReportMatchFacts facts) {
        return new PayPalReportMatchCandidateResponse(facts.recordId(), PayPalReportMatchKind.valueOf(facts.matchKind()), facts.confirmed(), facts.captureId(),
                facts.providerAmountMinor(), facts.providerCurrency());
    }
}
