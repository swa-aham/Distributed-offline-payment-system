package com.offlineupi.backend.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Hybrid encryption: RSA-OAEP (2048) wraps an AES-256-GCM key.
 *
 * Ciphertext layout (all bytes concatenated, then base64-encoded):
 *   [256 bytes]  RSA-OAEP encrypted AES key
 *   [12  bytes]  AES-GCM IV (nonce)
 *   [N   bytes]  AES-GCM ciphertext + 16-byte auth tag
 *
 * Why GCM? Authenticated encryption — any bit flip in transit throws on decrypt.
 * Why hybrid? RSA-2048 can only encrypt ~214 bytes; JSON payload exceeds that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridCryptoService {

    private static final int RSA_KEY_SIZE_BYTES = 256; // 2048 bits
    private static final int GCM_IV_BYTES       = 12;
    private static final int GCM_TAG_BITS       = 128;

    private final ServerKeyHolder keyHolder;

    // ── Encryption (used by edge node — replicated in ClientCryptoService) ────

    public String encrypt(String plaintext, PublicKey publicKey) throws Exception {
        byte[] plaintextBytes = plaintext.getBytes("UTF-8");

        // 1. Generate a fresh AES-256 key for this packet
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey aesKey = kg.generateKey();

        // 2. Encrypt plaintext with AES-256-GCM
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] aesCiphertext = aesCipher.doFinal(plaintextBytes);

        // 3. Encrypt AES key with RSA-OAEP
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // 4. Concatenate: [encryptedAesKey][iv][aesCiphertext]
        byte[] combined = new byte[encryptedAesKey.length + iv.length + aesCiphertext.length];
        System.arraycopy(encryptedAesKey, 0, combined, 0,                          encryptedAesKey.length);
        System.arraycopy(iv,             0, combined, encryptedAesKey.length,      iv.length);
        System.arraycopy(aesCiphertext,  0, combined, encryptedAesKey.length + iv.length, aesCiphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    // ── Decryption (server only — private key never leaves here) ─────────────

    public String decrypt(String ciphertextBase64) throws Exception {
        byte[] combined = Base64.getDecoder().decode(ciphertextBase64);

        // Split the combined bytes
        byte[] encryptedAesKey = Arrays.copyOfRange(combined, 0, RSA_KEY_SIZE_BYTES);
        byte[] iv              = Arrays.copyOfRange(combined, RSA_KEY_SIZE_BYTES, RSA_KEY_SIZE_BYTES + GCM_IV_BYTES);
        byte[] aesCiphertext   = Arrays.copyOfRange(combined, RSA_KEY_SIZE_BYTES + GCM_IV_BYTES, combined.length);

        // Decrypt AES key with RSA private key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, keyHolder.getPrivateKey());
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // Decrypt payload with AES-GCM (throws AEADBadTagException if tampered)
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = aesCipher.doFinal(aesCiphertext);

        return new String(plaintext, "UTF-8");
    }

    // ── Hashing (for idempotency key) ─────────────────────────────────────────

    public String sha256Hex(String ciphertextBase64) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(ciphertextBase64.getBytes("UTF-8"));
        return HexFormat.of().formatHex(hash);
    }
}
