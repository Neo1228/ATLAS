package com.atlas.settlement.service;

import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.dto.request.RefundPostingRequest;

public interface SettlementService {
    void recordCapture(CapturePostingRequest request);
    void recordRefund(RefundPostingRequest request);
}
