package com.atlas.settlement.service;

import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.dto.request.SellerSettlementStatementRequest;
import com.atlas.settlement.dto.response.SellerSettlementStatementResponse;


public interface SettlementService {
    void recordCapture(SettlementPostingRequest.Capture request);
    void recordCheckoutCapture(SettlementPostingRequest.CheckoutSettlement request);
    void recordRefund(SettlementPostingRequest.Refund request);
    void reserveRefund(SettlementPostingRequest.Refund request);
    void releaseRefundReservation(SettlementPostingRequest.Refund request);
    SellerSettlementStatementResponse getSellerStatement(SellerSettlementStatementRequest request);
}