package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.PayPalReportMatchResponse;
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
    public List<PayPalReportMatchResponse.Candidate> findByRecordIds(Collection<String> recordIds) {
        return repository.findByRecordIds(recordIds).stream()
                .map(PayPalReportMatchQueryImpl::toCandidate)
                .toList();
    }

    private static PayPalReportMatchResponse.Candidate toCandidate(PayPalReportMatchFacts facts) {
        return new PayPalReportMatchResponse.Candidate(facts.recordId(), PayPalReportMatchResponse.Kind.valueOf(facts.matchKind()), facts.confirmed(), facts.captureId(),
                facts.providerAmountMinor(), facts.providerCurrency());
    }
}
