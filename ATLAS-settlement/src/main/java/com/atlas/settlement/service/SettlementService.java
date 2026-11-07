package com.atlas.settlement.service;

import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.dto.request.CheckoutSettlementPostingRequest;
import com.atlas.settlement.dto.request.RefundPostingRequest;
import com.atlas.settlement.dto.request.SellerSettlementStatementRequest;
import com.atlas.settlement.dto.response.SellerSettlementStatementResponse;


public interface SettlementService {
    void recordCapture(CapturePostingRequest request);
    void recordCheckoutCapture(CheckoutSettlementPostingRequest request);
    void recordRefund(RefundPostingRequest request);
    SellerSettlementStatementResponse getSellerStatement(SellerSettlementStatementRequest request);
}
