package com.atlas.settlement.service;

import com.atlas.settlement.dto.request.CapturePostingRequest;

public interface SettlementService {
    void recordCapture(CapturePostingRequest request);
}
