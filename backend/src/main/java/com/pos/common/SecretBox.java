package com.pos.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pos.config.AppProperties;

/**
 * Encrypts secrets kept in the database (e.g. the Bakong API token) with AES-256-GCM. The key is
 * SECRETS_KEY (base64, 32 bytes); without it, one is derived from the JWT secret, so set a
 * dedicated key in production. Changing the key makes saved secrets unreadable: enter them again.
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(AppProperties props) {
        this.key = new SecretKeySpec(keyBytes(props), "AES");
    }

    static byte[] keyBytes(AppProperties props) {
        String configured = props.secretsKey();
        if (configured != null && !configured.isBlank()) {
            byte[] k = Base64.getDecoder().decode(configured.trim());
            if (k.length != 32) {
                throw new IllegalStateException("SECRETS_KEY must be 32 bytes, base64 encoded (openssl rand -base64 32)");
            }
            return k;
        }
        log.warn("SECRETS_KEY is not set; deriving the secrets key from the JWT secret. Set SECRETS_KEY in production.");
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("pos-secrets:" + props.jwt().secret()).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    /** Null when the value can't be read (e.g. the key changed). */
    public String decrypt(byte[] box) {
        if (box == null || box.length <= IV_BYTES) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, box, 0, IV_BYTES));
            return new String(c.doFinal(box, IV_BYTES, box.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            log.warn("A saved secret could not be decrypted (was SECRETS_KEY changed?)");
            return null;
        }
    }
}
