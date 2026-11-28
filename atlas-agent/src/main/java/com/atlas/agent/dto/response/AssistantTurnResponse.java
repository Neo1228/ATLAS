package com.atlas.agent.dto.response;

import com.atlas.order.dto.response.CheckoutPreviewResponse;
import java.util.List;
import java.util.UUID;

/**
 * 대화 한 차례의 결과. 금액과 결제 링크는 모델 문장이 아니라 서버가 계산한 구조화 필드를 정본으로 쓴다.
 *
 * @param reply 구매자에게 보여 줄 문장. 문장 속 금액이 서버 값과 다르면 서버가 만든 문장으로 바꾼다.
 * @param replyReplaced 위 교체가 일어났는지
 * @param cart 이번 차례 마지막 시점의 서버 계산 장바구니(도구가 장바구니를 읽거나 바꿨을 때만)
 * @param paymentApprovalUrl start_checkout이 만든 PayPal 승인 링크. 결제는 구매자가 PayPal에서 승인해야 진행된다.
 */
public record AssistantTurnResponse(UUID conversationId, String reply, boolean replyReplaced,
                                    List<AssistantToolCallSummary> toolCalls, CheckoutPreviewResponse cart,
                                    Long spendingCapWon, UUID checkoutId, String paymentApprovalUrl,
                                    long tokensUsed, long tokenBudget) {
    public AssistantTurnResponse {
        toolCalls = List.copyOf(toolCalls);
    }
}
