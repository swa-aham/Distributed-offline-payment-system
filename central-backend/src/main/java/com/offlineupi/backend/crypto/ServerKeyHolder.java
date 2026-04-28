package com.offlineupi.backend.crypto;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Holds the server's RSA-2048 key pair.
 *
 * In production: load private key from environment variable / AWS Secrets Manager.
 * For this demo: if RSA_PRIVATE_KEY env var is set, load it. Otherwise generate
 * a new pair and print the public key so the edge node can be configured with it.
 *
 * IMPORTANT: In a real deployment you generate once, store the private key securely,
 * and distribute the public key to all edge nodes at provisioning time.
 */
@Slf4j
@Component
public class ServerKeyHolder {

    @Value("${RSA_PRIVATE_KEY:}")
    private String privateKeyBase64;

    @Value("${RSA_PUBLIC_KEY:}")
    private String publicKeyBase64;

    @Getter
    private PrivateKey privateKey;

    @Getter
    private PublicKey publicKey;

    @PostConstruct
    public void init() throws Exception {
        if (!privateKeyBase64.isBlank() && !publicKeyBase64.isBlank()) {
            // Load from environment (production path)
            KeyFactory kf = KeyFactory.getInstance("RSA");
            byte[] privBytes = Base64.getDecoder().decode(privateKeyBase64);
            byte[] pubBytes  = Base64.getDecoder().decode(publicKeyBase64);
            privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            publicKey  = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
            log.info("RSA key pair loaded from environment variables.");
        } else {
            // Generate fresh pair (demo/dev path)
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair kp = gen.generateKeyPair();
            privateKey = kp.getPrivate();
            publicKey  = kp.getPublic();
            log.warn("========================================================");
            log.warn("RSA key pair GENERATED (not loaded from env).");
            log.warn("For production, set RSA_PRIVATE_KEY and RSA_PUBLIC_KEY.");
            log.warn("Public key (copy to edge node config):");
            log.warn(Base64.getEncoder().encodeToString(publicKey.getEncoded()));
            log.warn("========================================================");
        }
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
