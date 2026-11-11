package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sb24.api.config.AppProperties;
import com.sb24.api.crypto.JwtKeyProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeyProviderTest {
    private static AppProperties props(Path file, String pass, boolean generate) {
        return new AppProperties(new AppProperties.Security(file.toString(), pass, generate, null, Duration.ofMinutes(10), Duration.ofDays(7), 5,
                Duration.ofMinutes(15), List.of(), false, false), null, null, null, null, null, null);
    }

    @Test
    void keyIsGeneratedEncryptedAtRestAndReloadable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("jwt.enc");
        JwtKeyProvider first = new JwtKeyProvider(props(file, "a-long-passphrase-123", true));
        String pem = Files.readString(file);
        assertTrue(pem.startsWith("SB24-KEY-V1"), "private key must be stored encrypted (PBKDF2 + AES-GCM)");
        assertFalse(pem.contains("PRIVATE KEY"));
        JwtKeyProvider again = new JwtKeyProvider(props(file, "a-long-passphrase-123", false));
        assertEquals(first.keyId(), again.keyId());
        assertEquals(first.privateKey().getPrivateExponent(), again.privateKey().getPrivateExponent());
        assertEquals(3072, again.publicKey().getModulus().bitLength());
    }

    @Test
    void wrongPassphraseOrMissingKeyFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("jwt.enc");
        new JwtKeyProvider(props(file, "a-long-passphrase-123", true));
        assertThrows(Exception.class, () -> new JwtKeyProvider(props(file, "wrong-passphrase-12345", false)));
        assertThrows(IllegalStateException.class, () -> new JwtKeyProvider(props(dir.resolve("missing.enc"), "a-long-passphrase-123", false)));
        assertThrows(IllegalStateException.class, () -> new JwtKeyProvider(props(file, "short", true)));
    }
}
