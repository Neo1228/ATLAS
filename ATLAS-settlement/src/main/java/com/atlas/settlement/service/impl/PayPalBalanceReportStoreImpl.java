package com.atlas.settlement.service.impl;

import com.atlas.settlement.service.PayPalBalanceReportDisposition;
import com.atlas.settlement.service.PayPalBalanceReportRow;
import com.atlas.settlement.service.PayPalBalanceReportRun;
import com.atlas.settlement.service.PayPalBalanceReportRunSummary;
import com.atlas.settlement.repository.PayPalBalanceReportRunRepository;
import com.atlas.settlement.service.PayPalBalanceReportStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PayPalBalanceReportStoreImpl implements PayPalBalanceReportStore {
    private final PayPalBalanceReportRunRepository repository;

    public PayPalBalanceReportStoreImpl(PayPalBalanceReportRunRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<UUID> createRunIfAbsent(PayPalBalanceReportRun run, List<PayPalBalanceReportRow> rows) {
        return repository.createRunIfAbsent(run, rows);
    }

    @Override
    public Optional<PayPalBalanceReportRun> findRunById(UUID runId) {
        return repository.findRunById(runId);
    }

    @Override
    public Optional<PayPalBalanceReportRun> findRunBySha256(String reportSha256) {
        return repository.findRunBySha256(reportSha256);
    }

    @Override
    public List<PayPalBalanceReportRunSummary> listRunSummaries(int limit) {
        return repository.listRunSummaries(limit);
    }

    @Override
    public List<PayPalBalanceReportRow> listRows(UUID runId, int limit) {
        return repository.listRows(runId, limit);
    }

    @Override
    public List<PayPalBalanceReportRow> listAllRows(UUID runId) {
        return repository.listAllRows(runId);
    }

    @Override
    public Optional<UUID> insertDispositionIfAbsent(PayPalBalanceReportDisposition disposition) {
        return repository.insertDispositionIfAbsent(disposition);
    }

    @Override
    public Optional<PayPalBalanceReportDisposition> findDispositionByIdempotencyKey(String idempotencyKey) {
        return repository.findDispositionByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<PayPalBalanceReportDisposition> findDispositionHistory(UUID runId, int rowNumber) {
        return repository.findDispositionHistory(runId, rowNumber);
    }
}
