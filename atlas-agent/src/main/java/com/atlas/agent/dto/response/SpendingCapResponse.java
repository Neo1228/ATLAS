package com.atlas.agent.dto.response;

/** 구매자가 정한 쇼핑 비서 지출 상한. 정하지 않았으면 {@code capWon}이 null이고 비서는 checkout을 시작하지 않는다. */
public record SpendingCapResponse(Long capWon) {
}
