package com.atlas.application.controller.seller;

import com.atlas.settlement.dto.request.SellerSettlementStatementRequest;
import com.atlas.settlement.dto.response.SellerSettlementStatementResponse;
import com.atlas.settlement.service.SettlementService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers")
public class SellerSettlementStatementController {
    private final SettlementService settlements;

    @GetMapping("/{sellerId}/settlement-statement")
    public SellerSettlementStatementResponse getSellerStatement(
            @PathVariable UUID sellerId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String cursor) {
        return settlements.getSellerStatement(
                new SellerSettlementStatementRequest(sellerId, from, to, limit, cursor));
    }
}
