package com.offlineupi.edge.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Client-side encryption — mirrors HybridCryptoService on the backend.
 * Only encrypts (never decrypts — private key stays on the server).
 *
 * On startup, fetches the server's public key from GET /api/public-key.
 * In production, this key would be bundled at device provisioning time.
 */
@Slf4j
@Service
public class ClientCryptoService {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    @Value("${backend.url:http://localhost:8080}")
    private String backendUrl;

    private PublicKey serverPublicKey;

    @PostConstruct
    public void fetchServerPublicKey() {
        try {
            RestTemplate rt = new RestTemplate();
            Map response = rt.getForObject(backendUrl + "/api/public-key", Map.class);
            String base64Key = (String) response.get("publicKeyBase64");
            byte[] keyBytes  = Base64.getDecoder().decode(base64Key);
            serverPublicKey  = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
            log.info("Server public key fetched successfully.");
        } catch (Exception e) {
            log.warn("Could not fetch server public key (backend may be down): {}", e.getMessage());
            log.warn("Payments will be queued and will encrypt once key is available.");
        }
    }

    /**
     * Encrypt a JSON payload using hybrid RSA-OAEP + AES-256-GCM.
     * Returns base64-encoded ciphertext.
     */
    public String encrypt(String plaintextJson) throws Exception {
        if (serverPublicKey == null) {
            // Retry fetching key
            fetchServerPublicKey();
            if (serverPublicKey == null) {
                throw new IllegalStateException("Server public key not available — is the backend running?");
            }
        }

        byte[] plaintextBytes = plaintextJson.getBytes("UTF-8");

        // 1. Fresh AES-256 key per packet
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey aesKey = kg.generateKey();

        // 2. AES-256-GCM encrypt
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] aesCiphertext = aesCipher.doFinal(plaintextBytes);

        // 3. RSA-OAEP encrypt the AES key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, serverPublicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // 4. Concatenate [encryptedAesKey][iv][aesCiphertext]
        byte[] combined = new byte[encryptedAesKey.length + iv.length + aesCiphertext.length];
        System.arraycopy(encryptedAesKey, 0, combined, 0, encryptedAesKey.length);
        System.arraycopy(iv, 0, combined, encryptedAesKey.length, iv.length);
        System.arraycopy(aesCiphertext, 0, combined, encryptedAesKey.length + iv.length, aesCiphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }
}
