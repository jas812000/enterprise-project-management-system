package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DeferredScreeningCipherTest {
    @Test
    void encryptsAndAuthenticatesMetadata() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        var cipher = new DeferredScreeningCipher(key);
        byte[] ciphertext = cipher.encrypt("ABCDE012345", "credential-one");
        assertEquals("ABCDE012345", cipher.decrypt(ciphertext, "credential-one"));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(ciphertext, "credential-two"));
        ciphertext[ciphertext.length - 1] ^= 1;
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(ciphertext, "credential-one"));
    }
    @Test
    void rejectsInvalidKeyLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeferredScreeningCipher(new byte[16]));
    }
}
