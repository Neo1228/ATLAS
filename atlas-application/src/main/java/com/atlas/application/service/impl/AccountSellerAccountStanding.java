package com.atlas.application.service.impl;

import com.atlas.account.service.AccountService;
import com.atlas.catalog.service.SellerAccountStanding;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * catalog의 판매 차단 포트를 account 상태로 구현한다. catalog가 account에 의존하지 않도록 application이 잇는다.
 *
 * <p>요청마다 DB를 읽는다(캐시 없음). 정지 직후의 주문을 막는 것이 목적이라 지연을 두지 않는다.
 */
@Component
public class AccountSellerAccountStanding implements SellerAccountStanding {
    private final AccountService accounts;

    public AccountSellerAccountStanding(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean isDisabled(UUID sellerId) {
        return accounts.isSellerAccountDisabled(sellerId);
    }
}
