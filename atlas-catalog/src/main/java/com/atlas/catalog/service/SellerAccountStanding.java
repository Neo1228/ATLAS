package com.atlas.catalog.service;

import java.util.UUID;

/**
 * Seller 계정이 판매를 이어갈 수 있는 상태인지 묻는 포트다.
 *
 * <p>계정 상태는 account 모듈이 소유하고 catalog는 account에 의존하지 않는다. 그래서 판정은
 * application이 {@code AccountService#isSellerAccountDisabled}로 구현해 주입한다(ADR-0112).
 * catalog에 두는 이유는 판매 경로(admission, order)가 모두 catalog에 의존하는 가장 낮은 모듈이기 때문이다.
 */
public interface SellerAccountStanding {
    /** 연결된 계정이 정지되거나 폐쇄돼 판매·지급을 막아야 하면 {@code true}. */
    boolean isDisabled(UUID sellerId);
}
