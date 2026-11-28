package com.atlas.assistant.service;

import com.atlas.assistant.dto.request.AssistantTurnRequest;
import com.atlas.assistant.dto.response.AssistantToolResponse;
import com.atlas.assistant.dto.response.AssistantTurnResponse;
import com.atlas.assistant.dto.response.SpendingCapResponse;
import java.util.List;

/**
 * 대화형 쇼핑 비서(ADR-0109). 모든 메서드의 {@code buyerRef}는 인증 주체가 소유한 값이어야 한다
 * (HTTP 경로 변수로 받아 {@code AccountOwnershipInterceptor}가 확인한다). 도구 인자로는 구매자를 바꿀 수 없다.
 */
public interface ShoppingAssistantService {
    AssistantTurnResponse converse(String buyerRef, AssistantTurnRequest request);

    SpendingCapResponse findSpendingCap(String buyerRef);

    /** 구매자 HTTP 경로로만 바꾼다. 모델에 노출하는 도구에는 상한을 바꾸는 도구가 없다. */
    SpendingCapResponse setSpendingCap(String buyerRef, long capWon);

    void clearSpendingCap(String buyerRef);

    /** 모델에 노출하는 도구 목록(MCP tools/list 모양). */
    List<AssistantToolResponse> tools();
}
