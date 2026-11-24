package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuthenticationTokenMaterialTest {
    @Test
    void generatesIndependentOpaqueTokensAndCorrectDigests() {
        var generator = new AuthenticationTokenMaterial();
        var first = generator.generate();
        var second = generator.generate();
        assertEquals(43, first.value().length());
        assertNotEquals(first.value(), second.value());
        assertEquals(32, first.hash().length);
        assertArrayEquals(first.hash(), AuthenticationTokenMaterial.hashEncoded(first.value()));
        assertFalse(Arrays.equals(first.hash(), second.hash()));
        assertFalse(first.toString().contains(first.value()));
    }

    @Test
    void digestCannotBeMutatedThroughRecordAccessorOrConstructor() {
        var token = new AuthenticationTokenMaterial().generate();
        byte[] original = token.hash();
        byte[] extracted = token.hash();
        extracted[0] ^= 0x7f;
        assertArrayEquals(original, token.hash());
        byte[] source = original.clone();
        var copy = new AuthenticationTokenMaterial.Token(token.value(), source);
        source[0] ^= 0x7f;
        assertArrayEquals(original, copy.hash());
    }

    @Test
    void rejectsMalformedEncodings() {
        assertThrows(IllegalArgumentException.class, () -> AuthenticationTokenMaterial.hashEncoded(null));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationTokenMaterial.hashEncoded("abc"));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationTokenMaterial.hashEncoded("!".repeat(43)));
    }
}
