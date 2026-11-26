package com.atlas.application.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.account.service.impl.AccessTokenService;
import com.atlas.account.service.AccountService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
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
    private static final UUID FAMILY_ID = UUID.fromString("38c9e229-7f25-40a8-a96c-0d2bc697a45e");

    private final AccountJwtConfiguration configuration = new AccountJwtConfiguration();
    private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");
    private final JwtEncoder encoder = configuration.accountJwtEncoder(key);
    private final AccountService accounts = mock(AccountService.class);
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        when(accounts.isAccessTokenFamilyActive(ACCOUNT_ID, FAMILY_ID)).thenReturn(true);
        decoder = configuration.accountJwtDecoder(key, ISSUER, AUDIENCE, accounts);
    }

    @Test
    void signedAccessTokenCarriesExpectedIdentityAndClaims() {
        AccessTokenService tokens = new AccessTokenService(encoder, Clock.systemUTC(), ISSUER, AUDIENCE);

        Jwt decoded = decoder.decode(tokens.issue(ACCOUNT_ID, FAMILY_ID));

        assertEquals(ACCOUNT_ID.toString(), decoded.getSubject());
        assertEquals(ISSUER, decoded.getIssuer().toString());
        assertEquals(List.of(AUDIENCE), decoded.getAudience());
        assertEquals(AccessTokenService.ACCESS_TOKEN_LIFETIME_SECONDS,
                decoded.getExpiresAt().getEpochSecond() - decoded.getIssuedAt().getEpochSecond());
    }

    @Test
    void decoderRejectsInvalidSignature() {
        AccessTokenService tokens = new AccessTokenService(encoder, Clock.systemUTC(), ISSUER, AUDIENCE);
        String[] parts = tokens.issue(ACCOUNT_ID, FAMILY_ID).split("\\.");
        String tampered = parts[0] + "." + parts[1] + ".invalid-signature";

        assertThrows(JwtException.class, () -> decoder.decode(tampered));
    }

    @Test
    void decoderRejectsWrongIssuerAndAudience() {
        AccessTokenService wrongIssuer = new AccessTokenService(encoder, Clock.systemUTC(),
                "https://other.local", AUDIENCE);
        AccessTokenService wrongAudience = new AccessTokenService(encoder, Clock.systemUTC(),
                ISSUER, "other-api");

        assertThrows(JwtException.class, () -> decoder.decode(wrongIssuer.issue(ACCOUNT_ID, FAMILY_ID)));
        assertThrows(JwtException.class, () -> decoder.decode(wrongAudience.issue(ACCOUNT_ID, FAMILY_ID)));
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

    @Test
    void decoderRejectsTokensMissingRequiredClaims() throws Exception {
        String missingSubject = encodeWithoutClaim("sub");
        String missingIssuedAt = encodeWithoutClaim("iat");
        String missingExpiresAt = encodeWithoutClaim("exp");
        String missingFamily = encodeWithoutClaim("sid");

        assertMissingClaim(missingSubject, "sub");
        assertMissingClaim(missingIssuedAt, "iat");
        assertMissingClaim(missingExpiresAt, "exp");
        assertMissingClaim(missingFamily, "sid");
        assertThrows(JwtException.class, () -> decoder.decode(missingSubject));
        assertThrows(JwtException.class, () -> decoder.decode(missingIssuedAt));
        assertThrows(JwtException.class, () -> decoder.decode(missingExpiresAt));
        assertThrows(JwtException.class, () -> decoder.decode(missingFamily));
    }

    @Test
    void decoderAcceptsOnlyTheConfiguredHmacAlgorithm() throws Exception {
        // 같은 key로 정상 서명한 HS512 token도 거절돼야 알고리즘 허용 목록이 고정이라고 말할 수 있다(HS256 양성 대조군 포함).
        byte[] secret = new byte[64];
        Arrays.fill(secret, (byte) 7);
        JwtDecoder strict = configuration.accountJwtDecoder(new SecretKeySpec(secret, "HmacSHA256"), ISSUER, AUDIENCE, accounts);
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE)
                .subject(ACCOUNT_ID.toString()).claim("sid", FAMILY_ID.toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600))).build();
        SignedJWT hs256 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        hs256.sign(new MACSigner(secret));
        SignedJWT hs512 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        hs512.sign(new MACSigner(secret));

        assertEquals(ACCOUNT_ID.toString(), strict.decode(hs256.serialize()).getSubject());
        assertThrows(JwtException.class, () -> strict.decode(hs512.serialize()));
        assertThrows(JwtException.class, () -> strict.decode(new PlainJWT(claims).serialize()));
    }

    @Test
    void accessTokenIsAcceptedUntilItsExpiryPlusTheDefaultSixtySecondClockSkew() throws Exception {
        // 10분 수명의 허용 오차: 만료 30초 뒤까지는 받고 90초 뒤에는 거절한다(Spring 기본 skew 60초).
        Instant now = Instant.now();
        SignedJWT withinSkew = signed(now.minusSeconds(630), now.minusSeconds(30));
        SignedJWT beyondSkew = signed(now.minusSeconds(690), now.minusSeconds(90));

        assertEquals(ACCOUNT_ID.toString(), decoder.decode(withinSkew.serialize()).getSubject());
        assertThrows(JwtException.class, () -> decoder.decode(beyondSkew.serialize()));
    }

    private SignedJWT signed(Instant issuedAt, Instant expiresAt) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE)
                .subject(ACCOUNT_ID.toString()).claim("sid", FAMILY_ID.toString())
                .issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(key.getEncoded()));
        return jwt;
    }

    @Test
    void revokedRefreshFamilyInvalidatesPreviouslyIssuedAccessToken() {
        when(accounts.isAccessTokenFamilyActive(ACCOUNT_ID, FAMILY_ID)).thenReturn(false);
        AccessTokenService tokens = new AccessTokenService(encoder, Clock.systemUTC(), ISSUER, AUDIENCE);

        assertThrows(JwtException.class, () -> decoder.decode(tokens.issue(ACCOUNT_ID, FAMILY_ID)));
    }

    @Test
    void decoderRejectsMalformedFamilyAndFailsClosedWhenFamilyLookupIsUnavailable() throws Exception {
        JwtClaimsSet malformedFamilyClaims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(ACCOUNT_ID.toString())
                .claim("sid", "not-a-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        String malformedFamily = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), malformedFamilyClaims)).getTokenValue();
        assertThrows(JwtException.class, () -> decoder.decode(malformedFamily));

        AccessTokenService tokens = new AccessTokenService(encoder, Clock.systemUTC(), ISSUER, AUDIENCE);
        when(accounts.isAccessTokenFamilyActive(ACCOUNT_ID, FAMILY_ID))
                .thenThrow(new IllegalStateException("session status unavailable"));
        assertThrows(IllegalStateException.class,
                () -> decoder.decode(tokens.issue(ACCOUNT_ID, FAMILY_ID)));
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String encodeWithoutClaim(String omittedClaim) throws JOSEException, ParseException {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("sub", ACCOUNT_ID.toString());
        claims.put("sid", FAMILY_ID.toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(600).getEpochSecond());
        claims.remove(omittedClaim);
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), JWTClaimsSet.parse(claims));
        token.sign(new MACSigner(key.getEncoded()));
        return token.serialize();
    }

    private void assertMissingClaim(String token, String claim) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertFalse(payload.contains("\"" + claim + "\""));
    }

    @Test
    void blankSecretFailsStartupUnlessEphemeralKeyIsExplicitlyAllowed() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> configuration.accountJwtSigningKey("", false));
        assertThrows(IllegalStateException.class, () -> configuration.accountJwtSigningKey("  ", false));
        assertThrows(IllegalStateException.class, () -> configuration.accountJwtSigningKey(null, false));
        assertTrue(missing.getMessage().contains("ATLAS_JWT_SIGNING_SECRET"));
    }

    @Test
    void explicitEphemeralKeyOptInProducesDistinctProcessLocalKeys() {
        SecretKey first = configuration.accountJwtSigningKey("", true);
        SecretKey second = configuration.accountJwtSigningKey("", true);

        assertEquals(32, first.getEncoded().length);
        assertFalse(Arrays.equals(first.getEncoded(), second.getEncoded()));
    }

    @Test
    void configuredSecretIsUsedDeterministicallyEvenWhenEphemeralKeyIsNotAllowed() {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) 7);
        String encoded = Base64.getEncoder().encodeToString(raw);

        assertArrayEquals(raw, configuration.accountJwtSigningKey(encoded, false).getEncoded());
        assertThrows(IllegalStateException.class,
                () -> configuration.accountJwtSigningKey(Base64.getEncoder().encodeToString(new byte[16]), false));
    }
}
