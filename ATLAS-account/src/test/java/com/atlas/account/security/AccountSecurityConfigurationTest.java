package com.atlas.account.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountSecurityConfigurationTest {
    @Test
    void passwordEncoderSupportsMaximumSignupPasswordLength() {
        PasswordEncoder passwords = new AccountSecurityConfiguration().accountPasswordEncoder();
        String password = "a".repeat(128);

        String encoded = passwords.encode(password);

        assertTrue(encoded.startsWith("{pbkdf2}"));
        assertTrue(passwords.matches(password, encoded));
    }
}
