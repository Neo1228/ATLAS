package com.atlas.account.persistence.projection;

import java.util.UUID;

/** 이메일이 검증된 활성 Seller 계정을 조회한 값. 모듈 밖으로는 서비스가 응답 DTO로 바꿔 내보낸다. */
public record VerifiedSellerAccount(UUID accountId, String email) {
}
