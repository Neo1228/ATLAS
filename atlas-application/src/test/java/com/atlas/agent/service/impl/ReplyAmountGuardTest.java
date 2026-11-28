package com.atlas.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplyAmountGuardTest {
    private static final Set<Long> SERVER = Set.of(39_000L, 78_000L, 50_000L);
    private static final String LINK = "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T";

    @Test
    void acceptsServerAmountsInEveryCurrencyNotationAndTheIssuedApprovalLink() {
        assertThat(ReplyAmountGuard.matchesServer(
                "1개 39,000원, 2개면 78000 KRW, 상한은 ₩50,000이고 3만 9천원짜리도 있어요. 2개 담았습니다.",
                SERVER, null)).isTrue();
        assertThat(ReplyAmountGuard.matchesServer("승인 링크: " + LINK, SERVER, LINK)).isTrue();
    }

    @Test
    void rejectsAnyAmountOrLinkTheServerDidNotProduce() {
        assertThat(ReplyAmountGuard.matchesServer("합계 38,000원입니다", SERVER, null)).isFalse();
        assertThat(ReplyAmountGuard.matchesServer("3.9만원입니다", SERVER, null)).isFalse();
        assertThat(ReplyAmountGuard.matchesServer("About $29.90", SERVER, null)).isFalse();
        assertThat(ReplyAmountGuard.matchesServer("결제: https://paypal.evil.example/pay", SERVER, LINK)).isFalse();
        assertThat(ReplyAmountGuard.matchesServer("결제: " + LINK, SERVER, null)).isFalse();
    }
}
