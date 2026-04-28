package com.offlineupi.backend.controller;

import com.offlineupi.backend.crypto.ServerKeyHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KeyController {

    private final ServerKeyHolder keyHolder;

    /**
     * Edge nodes call this on startup to fetch the server's public key.
     * In production, the public key would be bundled at device provisioning time
     * and this endpoint would be authenticated.
     */
    @GetMapping("/public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKeyBase64", keyHolder.getPublicKeyBase64());
    }
}
