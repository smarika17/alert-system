package com.smarika.heartbeat.common;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class TokenService {

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken() {

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return HexFormat.of().formatHex(bytes);
    }

    public String hashToken(String token) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash =
                digest.digest(
                    token.getBytes(StandardCharsets.UTF_8)
                );
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Unable to hash token",
                e
            );
        }
    }
}