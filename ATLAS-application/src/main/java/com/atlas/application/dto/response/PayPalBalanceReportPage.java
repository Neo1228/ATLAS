package com.atlas.application.dto.response;

import java.util.List;

/** 페이지 크기가 제한된 조회 결과입니다. */
public record PayPalBalanceReportPage<T>(List<T> items, int limit, int offset, boolean hasMore) {
    public PayPalBalanceReportPage {
        items = List.copyOf(items);
    }
}
