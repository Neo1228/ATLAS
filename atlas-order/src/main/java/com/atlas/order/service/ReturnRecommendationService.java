package com.atlas.order.service;

import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.ReturnResponse;
import java.util.UUID;

/**
 * 판매자 반품 권고(ADR-0110). 읽기 전용이며 상태도 돈도 바꾸지 않는다. 환불은 판매자가
 * {@link OrderReturnService#inspect}를 호출할 때만 기존 경로로 실행된다.
 */
public interface ReturnRecommendationService {
    /** 수령했고 아직 검수 결과가 없는 반품만 권고한다. 줄마다 상태를 하나씩 받아야 한다. */
    ReturnResponse.Recommendation recommend(UUID sellerId, UUID orderId, UUID returnId,
            OrderRequest.ReturnRecommendation request);
}
