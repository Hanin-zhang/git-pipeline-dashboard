package com.githublook.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * GitHub signs the raw request body with HMAC-SHA256 and sends
 * {@code X-Hub-Signature-256: sha256=<hex>}. The comparison is constant time so a
 * timing oracle cannot be used to guess the secret byte by byte.
 */
@Slf4j
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    /**
     * @param payload  raw request body bytes, exactly as received (never re-serialised)
     * @param header   value of X-Hub-Signature-256
     * @param secret   shared secret for this repository
     */
    public boolean isValid(byte[] payload, String header, String secret) {
        if (payload == null || header == null || secret == null || secret.isEmpty()) {
            return false;
        }
        String normalized = header.trim();
        if (!normalized.startsWith(PREFIX)) {
            log.warn("Rejected webhook: unsupported signature scheme, expected {}", PREFIX);
            return false;
        }
        byte[] expected = compute(payload, secret);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(normalized.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            log.warn("Rejected webhook: signature is not valid hex");
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    public byte[] compute(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is not available in this JVM", e);
        }
    }

    /** Convenience for tests and the local webhook simulator. */
    public String sign(byte[] payload, String secret) {
        return PREFIX + HexFormat.of().formatHex(compute(payload, secret));
    }
}
