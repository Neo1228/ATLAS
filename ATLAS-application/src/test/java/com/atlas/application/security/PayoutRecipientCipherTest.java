package com.atlas.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PayoutRecipientCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsRecipientWithFreshIvAndRoundTrips() {
        PayoutRecipientCipher cipher = new PayoutRecipientCipher(KEY);

        String first = cipher.encrypt("seller@example.test");
        String second = cipher.encrypt("seller@example.test");

        assertThat(first).startsWith("v2:active:").doesNotContain("seller@example.test").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("seller@example.test");
    }


    @Test
    void fingerprintsAreStableKeyedAndNeverExposeTheRecipient() {
        PayoutRecipientCipher cipher = new PayoutRecipientCipher(KEY);
        String email = "seller@example.test";

        String first = cipher.fingerprint(email);
        String second = cipher.fingerprint(email);
        byte[] otherKeyBytes = new byte[32];
        otherKeyBytes[0] = 1;
        String otherKey = new PayoutRecipientCipher(Base64.getEncoder().encodeToString(otherKeyBytes))
                .fingerprint(email);

        assertThat(first).hasSize(64).isEqualTo(second).doesNotContain(email);
        assertThat(otherKey).isNotEqualTo(first);
    }


    @Test
    void decryptsPriorKeyIdDuringControlledRotation() {
        PayoutRecipientCipher oldCipher = new PayoutRecipientCipher(KEY, "key-old", "");
        String encrypted = oldCipher.encrypt("seller@example.test");
        byte[] newKeyBytes = new byte[32];
        newKeyBytes[0] = 2;
        String newKey = Base64.getEncoder().encodeToString(newKeyBytes);
        PayoutRecipientCipher rotated = new PayoutRecipientCipher(newKey, "key-new", "key-old=" + KEY);

        assertThat(rotated.decrypt(encrypted)).isEqualTo("seller@example.test");
        assertThat(rotated.encrypt("seller@example.test")).startsWith("v2:key-new:");
    }
    @Test
    void rejectsTamperedCiphertextAndInvalidEncryptionKey() {
        PayoutRecipientCipher cipher = new PayoutRecipientCipher(KEY);
        String encrypted = cipher.encrypt("seller@example.test");
        char replacement = encrypted.charAt(encrypted.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayoutRecipientCipher("not-base64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PayoutRecipientCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
