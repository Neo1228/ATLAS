package com.atlas.agent.dto.request;

import java.util.UUID;

/**
 * 대화 한 차례의 입력.
 *
 * @param conversationId 이어 갈 대화. null이면 새 대화를 만든다.
 * @param message 구매자 메시지(1~1000자).
 * @param confirmedTotalWon 구매자가 화면에서 결제 확인을 누른 서버 합계. 이 값이 현재 서버 합계와 같을 때만
 *                          이번 차례에 start_checkout을 실행한다(모델이 스스로 결제를 시작하지 못하게 하는 사람 확인).
 */
public record AssistantTurnRequest(UUID conversationId, String message, Long confirmedTotalWon) {
}
