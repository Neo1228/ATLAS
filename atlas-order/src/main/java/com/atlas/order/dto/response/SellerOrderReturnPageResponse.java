package com.atlas.order.dto.response;

import java.util.List;

public record SellerOrderReturnPageResponse(List<SellerOrderReturnResponse> returns, String nextCursor) {
    public SellerOrderReturnPageResponse {
        returns = List.copyOf(returns);
    }
}
