package com.atlas.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atlas.account.service.AccessTokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class AccountJwtConfigurationTest {
    private static final String ISSUER = "https://atlas.local";
    private static final String AUDIENCE = "atlas-api-test";
    private static final UUID ACCOUNT_ID = UUID.fromString("fb9d9240-689a-4e83-82f9-617a54fd02f8");

    private final AccountJwtConfiguration configuration = new AccountJwtConfiguration();
    private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");
    private final JwtEncoder encoder = configuration.accountJwtEncoder(key);
    private final JwtDecoder decoder = configuration.accountJwtDecoder(key, ISSUER, AUDIENCE);

    @Test
    void signedAccessTokenCarriesExpectedIdentityAndClaims() {
        AccessTokenService tokens = new AccessTokenService(encoder, Clock.systemUTC(), ISSUER, AUDIENCE);

        Jwt decoded = decoder.decode(tokens.issue(ACCOUNT_ID));

        assertEquals(ACCOUNT_ID.toString(), decoded.getSubject());
        assertEquals(ISSUER, decoded.getIssuer().toString());
        assertEquals(List.of(AUDIENCE), decoded.getAudience());
        assertEquals(AccessTokenService.ACCESS_TOKEN_LIFETIME_SECONDS,
                decoded.getExpiresAt().getEpochSecond() - decoded.getIssuedAt().getEpochSecond());
    }

    @Test
    void decoderRejectsInvalidSignature() {
        AccessTokenService tokens = new AccessTokenService(encoder, Clock.systemUTC(), ISSUER, AUDIENCE);
        String[] parts = tokens.issue(ACCOUNT_ID).split("\\.");
        String tampered = parts[0] + "." + parts[1] + ".invalid-signature";

        assertThrows(JwtException.class, () -> decoder.decode(tampered));
    }

    @Test
    void decoderRejectsWrongIssuerAndAudience() {
        AccessTokenService wrongIssuer = new AccessTokenService(encoder, Clock.systemUTC(),
                "https://other.local", AUDIENCE);
        AccessTokenService wrongAudience = new AccessTokenService(encoder, Clock.systemUTC(),
                ISSUER, "other-api");

        assertThrows(JwtException.class, () -> decoder.decode(wrongIssuer.issue(ACCOUNT_ID)));
        assertThrows(JwtException.class, () -> decoder.decode(wrongAudience.issue(ACCOUNT_ID)));
    }

    @Test
    void decoderRejectsExpiredAccessToken() {
        Instant now = Instant.now();
        JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(ACCOUNT_ID.toString())
                .issuedAt(now.minusSeconds(240))
                .expiresAt(now.minusSeconds(120))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, expiredClaims)).getTokenValue();

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

}
