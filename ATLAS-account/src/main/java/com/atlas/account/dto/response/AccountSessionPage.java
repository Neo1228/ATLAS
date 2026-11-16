package com.atlas.account.dto.response;

import java.util.List;

/** 제한된 계정 session 목록과 다음 항목 존재 여부입니다. */
public record AccountSessionPage(List<AccountSessionResponse> items, int limit, int offset, boolean hasMore) {
    public AccountSessionPage {
        items = List.copyOf(items);
    }
}
