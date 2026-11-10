package com.atlas.application;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
public class AccountJwtConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountJwtConfiguration.class);

    @Bean
    SecretKey accountJwtSigningKey(@Value("${atlas.security.jwt.signing-secret:}") String configuredSecret) {
        byte[] keyBytes;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            LOGGER.warn("JWT signing key가 임시 값입니다. 재시작하면 기존 access token이 무효화됩니다.");
        } else {
            try {
                keyBytes = Base64.getDecoder().decode(configuredSecret);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("ATLAS_JWT_SIGNING_SECRET must be Base64 encoded", exception);
            }
            if (keyBytes.length < 32) {
                throw new IllegalStateException("ATLAS_JWT_SIGNING_SECRET must decode to at least 32 bytes");
            }
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder accountJwtEncoder(SecretKey accountJwtSigningKey) {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(accountJwtSigningKey)
                .algorithm(JWSAlgorithm.HS256)
                .keyID("atlas-local-hs256")
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
    }

    @Bean
    JwtDecoder accountJwtDecoder(SecretKey accountJwtSigningKey,
            @Value("${atlas.security.jwt.issuer:http://127.0.0.1:8080}") String issuer,
            @Value("${atlas.security.jwt.audience:atlas-api}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(accountJwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", values -> values != null && values.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }
}
