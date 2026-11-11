package com.sb24.api.crypto;

import com.sb24.api.config.AppProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RSA signing key (RS256) with key stretching at rest. The private key (PKCS#8) is encrypted with AES-256-GCM under a
 * key derived from the passphrase with PBKDF2-HMAC-SHA256 (310,000 iterations, random 16-byte salt). Stealing the file
 * therefore does not give a usable signing key, and every passphrase guess costs 310k HMAC iterations. The salt,
 * iteration count and IV are stored in the file header so the cost can be raised later without breaking old files.
 *
 * File format (text): "SB24-KEY-V1" newline, then base64 of  iterations(4) | salt(16) | iv(12) | ciphertext+tag.
 */
@Component
public class JwtKeyProvider {
    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);
    static final int ITERATIONS = 310_000; // OWASP minimum for PBKDF2-HMAC-SHA256
    static final String MAGIC = "SB24-KEY-V1";

    private final RSAPrivateCrtKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public JwtKeyProvider(AppProperties props) throws GeneralSecurityException, IOException {
        String passphrase = props.security().jwtKeyPassphrase();
        if (passphrase == null || passphrase.length() < 16) {
            throw new IllegalStateException("APP_SECURITY_JWT_KEY_PASSPHRASE must be set (>= 16 characters)");
        }
        char[] pass = passphrase.toCharArray();
        String location = props.security().jwtKeyPath();
        boolean mayGenerate = props.security().allowKeyGeneration();
        KeyPair pair;
        if (location == null || location.isBlank()) {
            if (!mayGenerate) throw new IllegalStateException("APP_SECURITY_JWT_KEY_PATH is required");
            log.warn("JWT signing key is EPHEMERAL (no path configured): tokens die with the process");
            pair = generate();
        } else {
            Path path = Path.of(location);
            if (Files.exists(path)) {
                pair = load(path, pass);
            } else if (mayGenerate) {
                pair = generate();
                store(path, pair.getPrivate(), pass);
                log.warn("Generated a new encrypted JWT signing key at {}", path);
            } else {
                throw new IllegalStateException("JWT key file not found: " + path);
            }
        }
        this.privateKey = (RSAPrivateCrtKey) pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
        this.keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
    }

    public RSAPrivateCrtKey privateKey() { return privateKey; }
    public RSAPublicKey publicKey() { return publicKey; }
    public String keyId() { return keyId; }

    static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(3072);
        return g.generateKeyPair();
    }

    static void store(Path path, PrivateKey key, char[] passphrase) throws GeneralSecurityException, IOException {
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[16], iv = new byte[12];
        rng.nextBytes(salt);
        rng.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, stretch(passphrase, salt, ITERATIONS), new GCMParameterSpec(128, iv));
        c.updateAAD(MAGIC.getBytes(StandardCharsets.US_ASCII));
        byte[] ct = c.doFinal(key.getEncoded());
        ByteBuffer buf = ByteBuffer.allocate(4 + salt.length + iv.length + ct.length);
        buf.putInt(ITERATIONS).put(salt).put(iv).put(ct);
        String text = MAGIC + "\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(buf.array()) + "\n";
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) { /* non-POSIX file system */ }
    }

    static KeyPair load(Path path, char[] passphrase) throws GeneralSecurityException, IOException {
        String text = Files.readString(path);
        if (!text.startsWith(MAGIC)) throw new GeneralSecurityException("unknown key file format");
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(text.substring(MAGIC.length()).replaceAll("\\s", "")));
        int iterations = buf.getInt();
        if (iterations < 100_000) throw new GeneralSecurityException("key file uses too few PBKDF2 iterations");
        byte[] salt = new byte[16], iv = new byte[12];
        buf.get(salt).get(iv);
        byte[] ct = new byte[buf.remaining()];
        buf.get(ct);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, stretch(passphrase, salt, iterations), new GCMParameterSpec(128, iv));
        c.updateAAD(MAGIC.getBytes(StandardCharsets.US_ASCII));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(c.doFinal(ct))); // wrong passphrase => AEAD tag mismatch
        PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        return new KeyPair(pub, priv);
    }

    private static SecretKeySpec stretch(char[] passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(passphrase, salt, iterations, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }
}
