package com.saga.orchestrator.infrastructure;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Derives deterministic idempotency keys via SHA-256.
 *
 * Forward key:      SHA-256(sagaId + ":fwd:"  + stepIndex + ":" + attempt)
 * Compensation key: SHA-256(sagaId + ":comp:" + stepIndex + ":" + attempt)
 *
 * The ":" separators prevent collisions between inputs like (step=12, attempt=3)
 * and (step=1, attempt=23) — without separators both would produce "...123".
 */
@Component
public class IdempotencyKeyGenerator {

    public String forwardKey(UUID sagaId, int stepIndex, int attempt) {
        return sha256(sagaId + ":fwd:" + stepIndex + ":" + attempt);
    }

    public String compensationKey(UUID sagaId, int stepIndex, int attempt) {
        return sha256(sagaId + ":comp:" + stepIndex + ":" + attempt);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }
}
