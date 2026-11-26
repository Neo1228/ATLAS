package com.atlas.application.security;

import com.atlas.account.service.AccountService;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * account access JWT를 지급 운영자 인증으로 바꾼다.
 *
 * <p>서명·만료·refresh family 확인은 JWT decoder가 이미 했다. 여기서는 그 account가 지금 지급 운영자인지
 * 요청마다 DB로 확인한다. 권한 해제와 계정 정지가 토큰 수명(10분)을 기다리지 않고 바로 적용되게 하려는 것이다.
 * 운영자가 아니면 권한 없는 인증을 돌려줘 chain이 403으로 거절한다.
 *
 * <p>토큰의 {@code sid}(세션 family)가 2차 인증을 거쳐 만들어졌는지도 같은 쿼리에서 본다(ADR-0094). JWT에 인증 수단
 * claim을 넣지 않고 DB 표식을 읽는 이유는, 갱신으로 받은 access token도 같은 세션이라 표식이 이어지고, 표식이 없는
 * 오래된 세션은 토큰 수명을 기다리지 않고 다음 요청부터 거절되기 때문이다.
 *
 * <p>일부러 {@code JwtAuthenticationToken}이 아닌 토큰을 만든다. {@link AccountOwnershipInterceptor}는
 * {@code JwtAuthenticationToken}을 Buyer·Seller 소유권 검사 대상으로 보기 때문에, 소유 개념이 없는 운영자는 그
 * 검사의 대상이 아니라는 것을 타입으로 드러낸다. principal 이름은 account id이고 감사 actor가 된다.
 */
public class PayoutOperatorAuthenticationConverter implements Converter<Jwt, UsernamePasswordAuthenticationToken> {
    private final AccountService accounts;
    private final String role;

    public PayoutOperatorAuthenticationConverter(AccountService accounts, String role) {
        this.accounts = accounts;
        this.role = role;
    }

    @Override
    public UsernamePasswordAuthenticationToken convert(Jwt jwt) {
        UUID accountId = UUID.fromString(jwt.getSubject());
        String sid = jwt.getClaimAsString("sid");
        UUID sessionFamilyId = sid == null ? null : UUID.fromString(sid);
        List<GrantedAuthority> authorities = accounts.isPayoutOperator(accountId, sessionFamilyId)
                ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                : List.of();
        return UsernamePasswordAuthenticationToken.authenticated(accountId.toString(), null, authorities);
    }
}
