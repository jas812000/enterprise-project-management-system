package com.jamesstevens.epms.security.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;

/** AES-256-GCM envelope for temporary screening metadata. Keys must be supplied externally. */
public final class DeferredScreeningCipher {
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_TAG_BYTES = 16;
    private static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public DeferredScreeningCipher(byte[] keyBytes) {
        Objects.requireNonNull(keyBytes);
        if (keyBytes.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key required");
        }
        key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    public static DeferredScreeningCipher fromEnvironment() {
        String encoded = System.getenv("EPMS_SCREENING_ENCRYPTION_KEY");
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Missing screening encryption key");
        }
        return new DeferredScreeningCipher(Base64.getDecoder().decode(encoded));
    }

    public byte[] encrypt(String screeningDigest, String credentialId) {
        return crypt(Cipher.ENCRYPT_MODE, screeningDigest.getBytes(StandardCharsets.US_ASCII),
                credentialId, null);
    }

    public String decrypt(byte[] encrypted, String credentialId) {
        return new String(crypt(Cipher.DECRYPT_MODE, encrypted, credentialId, null),
                StandardCharsets.US_ASCII);
    }

    private byte[] crypt(int mode, byte[] data, String credentialId, byte[] unused) {
        try {
            Objects.requireNonNull(credentialId);
            byte[] nonce;
            byte[] payload;
            if (mode == Cipher.ENCRYPT_MODE) {
                nonce = new byte[NONCE_SIZE];
                random.nextBytes(nonce);
                payload = data;
            } else {
                if (data.length < NONCE_SIZE + GCM_TAG_BYTES) {
                    throw new IllegalArgumentException("Invalid ciphertext");
                }
                nonce = new byte[NONCE_SIZE];
                System.arraycopy(data, 0, nonce, 0, NONCE_SIZE);
                payload = java.util.Arrays.copyOfRange(data, NONCE_SIZE, data.length);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(credentialId.getBytes(StandardCharsets.UTF_8));
            byte[] result = cipher.doFinal(payload);
            return mode == Cipher.DECRYPT_MODE
                    ? result
                    : ByteBuffer.allocate(NONCE_SIZE + result.length)
                            .put(nonce).put(result).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Screening metadata encryption failed", exception);
        }
    }
}
