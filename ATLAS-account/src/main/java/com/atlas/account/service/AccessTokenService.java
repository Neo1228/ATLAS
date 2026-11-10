package com.atlas.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {
    public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(10);
    public static final long ACCESS_TOKEN_LIFETIME_SECONDS = ACCESS_TOKEN_LIFETIME.toSeconds();

    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;

    public AccessTokenService(JwtEncoder encoder, Clock clock,
            @Value("${atlas.security.jwt.issuer:http://127.0.0.1:8080}") String issuer,
            @Value("${atlas.security.jwt.audience:atlas-api}") String audience) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
    }

    public String issue(UUID accountId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(accountId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_LIFETIME))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
