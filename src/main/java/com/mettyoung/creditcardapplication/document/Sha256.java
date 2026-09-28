package com.mettyoung.creditcardapplication.document;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.HexFormat;
import java.util.Locale;

/**
 * A lower-case hex SHA-256 digest. Validated in the constructor, so every instance is valid.
 *
 * @throws InvalidDocumentException if absent or not 64 hex characters
 */
public record Sha256(@JsonValue String value) {

    public static final int HEX_LENGTH = 64;

    static final String FIELD = "sha256";

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Sha256 {
        if (value == null) {
            throw new InvalidDocumentException(FIELD, FIELD + " is required.");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() != HEX_LENGTH) {
            throw new InvalidDocumentException(FIELD, FIELD + " must be " + HEX_LENGTH + " hex characters.");
        }
        if (!value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new InvalidDocumentException(FIELD, FIELD + " must be hexadecimal.");
        }
    }

    public static Sha256 of(byte[] digest) {
        return new Sha256(HexFormat.of().formatHex(digest));
    }

    /**
     * No production caller: {@code DocumentService} compares the digests in base64, which is the form the
     * store returns, so the two never meet as {@code Sha256}. Only its own spec exercises it.
     * <p>
     * Constant time is a habit here rather than a defence — neither side is a secret the caller is guessing,
     * since the client declared one and uploaded the bytes behind the other. It earns its name in HMAC
     * verification, which arrives with FR5.
     */
    public boolean matches(Sha256 other) {
        if (other == null) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < HEX_LENGTH; i++) {
            difference |= value.charAt(i) ^ other.value.charAt(i);
        }
        return difference == 0;
    }
}
