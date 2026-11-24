package com.atlas.application.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** AES-GCM protection for payout recipient snapshots persisted in the outbox. */
@Component
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class PayoutRecipientCipher {
    private static final String LEGACY_PREFIX = "v1:";
    private static final String CURRENT_VERSION = "v2";
    private static final byte[] LEGACY_PURPOSE =
            "ATLAS_PAYPAL_PAYOUT_RECIPIENT:v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINGERPRINT_PURPOSE =
            "ATLAS_PAYPAL_PAYOUT_RECIPIENT_FINGERPRINT:v2".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String activeKeyId;
    private final SecretKeySpec activeKey;
    private final Map<String, SecretKeySpec> decryptionKeys;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PayoutRecipientCipher(
            @Value("${atlas.payment.paypal.payouts.recipient-encryption-key:}") String encodedKey,
            @Value("${atlas.payment.paypal.payouts.recipient-encryption-key-id:active}") String configuredKeyId,
            @Value("${atlas.payment.paypal.payouts.recipient-decryption-keys:}") String previousKeys) {
        activeKeyId = validateKeyId(configuredKeyId);
        byte[] activeBytes = decodeKey(encodedKey);
        Map<String, SecretKeySpec> keys = new HashMap<>();
        activeKey = new SecretKeySpec(activeBytes, "AES");
        keys.put(activeKeyId, activeKey);
        Arrays.fill(activeBytes, (byte) 0);
        addPreviousKeys(keys, previousKeys);
        decryptionKeys = Map.copyOf(keys);
    }

    /** Convenience constructor for deterministic unit tests. */
    public PayoutRecipientCipher(String encodedKey) {
        this(encodedKey, "active", "");
    }

    public String encrypt(String recipient) {
        validateRecipient(recipient);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(currentPurpose(activeKeyId));
            byte[] encrypted = cipher.doFinal(recipient.trim().getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
            return CURRENT_VERSION + ":" + activeKeyId + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to protect PayPal payout recipient", exception);
        }
    }

    /** Keyed fingerprint for idempotency checks; an offline dictionary cannot recover the email. */
    public String fingerprint(String recipient) {
        validateRecipient(recipient);
        byte[] keyBytes = activeKey.getEncoded();
        byte[] derivedKey = null;
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            derivedKey = derivation.doFinal(FINGERPRINT_PURPOSE);
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(derivedKey, "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(recipient.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to fingerprint PayPal payout recipient", exception);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            if (derivedKey != null) Arrays.fill(derivedKey, (byte) 0);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) throw new IllegalArgumentException("Payout recipient ciphertext is required");
        SecretKeySpec decryptionKey;
        byte[] purpose;
        String encodedEnvelope;
        if (ciphertext.startsWith(LEGACY_PREFIX)) {
            // v1 had no key id; retain the original key under the reserved legacy-v1 id during rotation.
            decryptionKey = decryptionKeys.getOrDefault("legacy-v1", activeKey);
            purpose = LEGACY_PURPOSE;
            encodedEnvelope = ciphertext.substring(LEGACY_PREFIX.length());
        } else {
            String[] parts = ciphertext.split(":", 3);
            if (parts.length != 3 || !CURRENT_VERSION.equals(parts[0]) || !validKeyId(parts[1])) {
                throw new IllegalArgumentException("Unsupported payout recipient ciphertext version");
            }
            decryptionKey = decryptionKeys.get(parts[1]);
            if (decryptionKey == null) {
                throw new IllegalArgumentException("Payout recipient decryption key is unavailable");
            }
            purpose = currentPurpose(parts[1]);
            encodedEnvelope = parts[2];
        }
        byte[] envelope;
        try {
            envelope = Base64.getUrlDecoder().decode(encodedEnvelope);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid payout recipient ciphertext", exception);
        }
        if (envelope.length <= IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("Invalid payout recipient ciphertext length");
        }
        byte[] iv = Arrays.copyOfRange(envelope, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(envelope, IV_BYTES, envelope.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(purpose);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to decrypt payout recipient ciphertext", exception);
        }
    }

    private static byte[] decodeKey(String encodedKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(Objects.requireNonNullElse(encodedKey, ""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PayPal payout recipient encryption key is invalid", exception);
        }
        if (bytes.length != 32) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalStateException("PayPal payout recipient encryption key must decode to 32 bytes");
        }
        return bytes;
    }

    private static void addPreviousKeys(Map<String, SecretKeySpec> keys, String configuredKeys) {
        if (configuredKeys == null || configuredKeys.isBlank()) return;
        for (String entry : configuredKeys.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || !validKeyId(parts[0]) || keys.containsKey(parts[0])) {
                throw new IllegalStateException("Payout recipient decryption keyring is invalid");
            }
            byte[] bytes = decodeKey(parts[1]);
            keys.put(parts[0], new SecretKeySpec(bytes, "AES"));
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String validateKeyId(String keyId) {
        if (!validKeyId(keyId)) throw new IllegalStateException("Payout recipient key id is invalid");
        return keyId;
    }

    private static boolean validKeyId(String keyId) {
        return keyId != null && keyId.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static byte[] currentPurpose(String keyId) {
        return ("ATLAS_PAYPAL_PAYOUT_RECIPIENT:v2:" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static void validateRecipient(String recipient) {
        if (recipient == null || recipient.isBlank() || recipient.length() > 320 || !recipient.contains("@")) {
            throw new IllegalArgumentException("A verified payout recipient email is required");
        }
    }
}
