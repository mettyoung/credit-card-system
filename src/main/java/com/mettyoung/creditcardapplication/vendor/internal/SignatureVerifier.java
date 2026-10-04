package com.mettyoung.creditcardapplication.vendor.internal;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Onfido's webhook signature: hex HMAC-SHA256 of the <em>raw</em> request body, keyed by the webhook token.
 * <p>
 * The raw bytes matter. Parsing the body to JSON and re-serialising it can reorder fields, which changes the
 * digest and rejects a genuine callback.
 * <p>
 * Onfido signs the body alone and sends no timestamp, so a captured request stays valid forever and replay
 * cannot be prevented here. Two other things make that harmless: the event id is unique in
 * {@code vendor_inbox}, so a replay inserts nothing; and the result is always fetched by check id, so a replay
 * can at worst make us re-read a check we already own.
 */
@Component
class SignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    SignatureVerifier(OnfidoProperties properties) {
        this.key = properties.webhookToken().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        byte[] expected = hmac(rawBody);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.strip().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Constant time: a length-independent early exit would leak the digest a byte at a time.
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot compute webhook signature", e);
        }
    }
}
