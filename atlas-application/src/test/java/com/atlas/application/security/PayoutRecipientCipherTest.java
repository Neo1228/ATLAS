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
        // 암호문 바이트 하나를 뒤집어 확실히 변조한다. base64url 문자열의 끝 글자는 쓰이지 않는 비트를 가질 수 있어
        // 그 글자만 바꾸면 디코딩 결과가 같아 변조가 되지 않는다(이 봉투 길이에서 약 6%).
        int envelopeStart = encrypted.lastIndexOf(':') + 1;
        byte[] envelope = Base64.getUrlDecoder().decode(encrypted.substring(envelopeStart));
        envelope[envelope.length - 1] ^= 0x01;
        String tampered = encrypted.substring(0, envelopeStart)
                + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayoutRecipientCipher("not-base64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PayoutRecipientCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
