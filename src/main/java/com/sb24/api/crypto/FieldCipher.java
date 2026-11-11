package com.sb24.api.crypto;

import com.sb24.api.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Confidentiality + integrity primitives, all derived from ONE master key (APP_SECURITY_DATA_KEY) with HMAC-based
 * domain separation, so a leak of one derived key does not expose the others:
 *  - encrypt/decrypt : AES-256-GCM with a random 96-bit IV per value (authenticated: tampering is detected);
 *  - blindIndex      : HMAC-SHA256, lets us enforce uniqueness / look up a value without storing it in clear;
 *  - ledgerMac       : HMAC-SHA256 used to chain ledger rows.
 */
@Component
public class FieldCipher {
    private static final String PREFIX = "v1:";
    private final SecureRandom rng = new SecureRandom();
    private final SecretKey encKey;
    private final SecretKey indexKey;
    private final SecretKey ledgerKey;
    private final SecretKey auditKey;

    public FieldCipher(AppProperties props) {
        String b64 = props.security().dataKey();
        byte[] master;
        try {
            master = Base64.getDecoder().decode(b64 == null ? "" : b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_SECURITY_DATA_KEY must be base64", e);
        }
        if (master.length != 32) {
            throw new IllegalStateException("APP_SECURITY_DATA_KEY must decode to exactly 32 bytes (openssl rand -base64 32)");
        }
        this.encKey = new SecretKeySpec(derive(master, "sb24/field-encryption"), "AES");
        this.indexKey = new SecretKeySpec(derive(master, "sb24/blind-index"), "HmacSHA256");
        this.ledgerKey = new SecretKeySpec(derive(master, "sb24/ledger-integrity"), "HmacSHA256");
        this.auditKey = new SecretKeySpec(derive(master, "sb24/audit-log"), "HmacSHA256");
        Arrays.fill(master, (byte) 0);
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (!stored.startsWith(PREFIX)) throw new IllegalStateException("unknown ciphertext format");
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed (wrong key or tampered data)", e);
        }
    }

    public String blindIndex(String normalizedValue) { return hex(hmac(indexKey, normalizedValue)); }

    public String ledgerMac(String data) { return hex(hmac(ledgerKey, data)); }

    public String auditMac(String data) { return hex(hmac(auditKey, data)); }

    private static byte[] derive(byte[] master, String label) { return hmac(new SecretKeySpec(master, "HmacSHA256"), label); }

    private static byte[] hmac(SecretKey key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
}
