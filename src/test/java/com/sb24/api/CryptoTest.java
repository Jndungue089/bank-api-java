package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sb24.api.config.AppProperties;
import com.sb24.api.crypto.FieldCipher;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CryptoTest {
    private static AppProperties props(String key) {
        return new AppProperties(new AppProperties.Security(null, null, true, key, Duration.ofMinutes(10), Duration.ofDays(7), 5,
                Duration.ofMinutes(15), java.util.List.of(), false, false), null, null, null, null, null, null);
    }

    private static final String KEY = "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMDEyMzQ=";

    @Test
    void aesGcmRoundTripUsesRandomIvAndDetectsTampering() {
        FieldCipher c = new FieldCipher(props(KEY));
        String a = c.encrypt("004567890LA041"), b = c.encrypt("004567890LA041");
        assertNotEquals(a, b, "same plaintext must not give the same ciphertext (random IV)");
        assertEquals("004567890LA041", c.decrypt(a));
        char flipped = a.charAt(a.length() - 6) == 'A' ? 'B' : 'A';
        String tampered = a.substring(0, a.length() - 6) + flipped + a.substring(a.length() - 5);
        assertThrows(IllegalStateException.class, () -> c.decrypt(tampered));
    }

    @Test
    void wrongKeyCannotDecryptAndBadKeysAreRefused() {
        FieldCipher c = new FieldCipher(props(KEY));
        FieldCipher other = new FieldCipher(props("b3RoZXIta2V5LW90aGVyLWtleS1vdGhlci1rZXktMDE="));
        assertThrows(IllegalStateException.class, () -> other.decrypt(c.encrypt("secret")));
        assertThrows(IllegalStateException.class, () -> new FieldCipher(props("c2hvcnQ=")));
        assertThrows(IllegalStateException.class, () -> new FieldCipher(props(null)));
    }

    @Test
    void blindIndexIsDeterministicKeyedAndDomainSeparated() {
        FieldCipher c = new FieldCipher(props(KEY));
        assertEquals(c.blindIndex("123"), c.blindIndex("123"));
        assertNotEquals(c.blindIndex("123"), c.blindIndex("124"));
        assertNotEquals(c.blindIndex("123"), c.ledgerMac("123"), "derived keys must be independent");
        assertNotEquals(c.blindIndex("123"), new FieldCipher(props("b3RoZXIta2V5LW90aGVyLWtleS1vdGhlci1rZXktMDE=")).blindIndex("123"));
    }
}
