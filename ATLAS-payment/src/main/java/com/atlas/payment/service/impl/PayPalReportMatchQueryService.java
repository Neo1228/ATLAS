package com.atlas.payment.service.impl;

import com.atlas.payment.persistence.projection.PayPalReportMatchFacts;
import com.atlas.payment.repository.PayPalReportMatchRepository;
import com.atlas.payment.service.PayPalReportMatchQuery;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayPalReportMatchQueryService implements PayPalReportMatchQuery {
    private final PayPalReportMatchRepository repository;

    public PayPalReportMatchQueryService(PayPalReportMatchRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> findByRecordIds(Collection<String> recordIds) {
        return repository.findByRecordIds(recordIds).stream()
                .map(PayPalReportMatchQueryService::toCandidate)
                .toList();
    }

    private static Candidate toCandidate(PayPalReportMatchFacts facts) {
        return new Candidate(facts.recordId(), Kind.valueOf(facts.matchKind()), facts.confirmed(), facts.captureId(),
                facts.providerAmountMinor(), facts.providerCurrency());
    }
}
