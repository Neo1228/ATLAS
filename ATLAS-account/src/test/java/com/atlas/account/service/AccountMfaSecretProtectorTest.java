package com.atlas.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AccountMfaSecretProtectorTest {
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsAndDecryptsWithConfiguredKey() {
        AccountMfaSecretProtector protector = new AccountMfaSecretProtector(ENCRYPTION_KEY);

        String encrypted = protector.encrypt("BASE32SECRET");

        assertNotEquals("BASE32SECRET", encrypted);
        assertEquals("BASE32SECRET", protector.decrypt(encrypted));
    }

    @Test
    void rejectsMissingOrInvalidKeyWhenEncryptionIsRequested() {
        AccountMfaSecretProtector protector = new AccountMfaSecretProtector("");

        assertThrows(IllegalStateException.class, () -> protector.encrypt("BASE32SECRET"));
    }

    @Test
    void rejectsWrongKeyAndTamperedCiphertext() {
        AccountMfaSecretProtector protector = new AccountMfaSecretProtector(ENCRYPTION_KEY);
        String encrypted = protector.encrypt("BASE32SECRET");
        byte[] differentKey = new byte[32];
        differentKey[0] = 1;
        AccountMfaSecretProtector wrongKey = new AccountMfaSecretProtector(
                Base64.getEncoder().encodeToString(differentKey));
        byte[] tamperedBytes = Base64.getDecoder().decode(encrypted);
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(tamperedBytes);

        assertThrows(IllegalStateException.class, () -> wrongKey.decrypt(encrypted));
        assertThrows(IllegalStateException.class, () -> protector.decrypt(tampered));
        assertThrows(IllegalArgumentException.class, () -> protector.decrypt("not-base64!"));
    }
}