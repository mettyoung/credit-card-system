package com.mettyoung.creditcardapplication.document;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The accepted upload content types. An enum rather than a validating record, because the allow-list is
 * closed and each entry carries the magic bytes the object must actually start with — a declared type is a
 * claim, and {@code /complete} checks it against the first bytes of the object.
 */
public enum ContentType {

    JPEG("image/jpeg", 0xFF, 0xD8, 0xFF),
    PNG("image/png", 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A),
    PDF("application/pdf", '%', 'P', 'D', 'F', '-');

    static final String FIELD = "contentType";

    private final String mediaType;
    private final byte[] magic;

    ContentType(String mediaType, int... magic) {
        this.mediaType = mediaType;
        this.magic = new byte[magic.length];
        for (int i = 0; i < magic.length; i++) {
            this.magic[i] = (byte) magic[i];
        }
    }

    /**
     * Also the binder for the request field, so an unknown type is refused before the service sees it.
     *
     * @throws InvalidDocumentException if absent or not on the allow-list
     */
    @JsonCreator
    public static ContentType of(String raw) {
        if (raw == null) {
            throw new InvalidDocumentException(FIELD, FIELD + " is required.");
        }
        String candidate = raw.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.mediaType.equals(candidate))
                .findFirst()
                .orElseThrow(() -> new InvalidDocumentException(FIELD,
                        FIELD + " must be one of " + String.join(", ", mediaTypes()) + "."));
    }

    public static List<String> mediaTypes() {
        return Arrays.stream(values()).map(ContentType::mediaType).toList();
    }

    /** How many leading bytes to read for {@link #matchesMagicBytes(byte[])} to be able to decide. */
    public static int magicBytesToRead() {
        return Arrays.stream(values()).mapToInt(type -> type.magic.length).max().orElseThrow();
    }

    public String mediaType() {
        return mediaType;
    }

    /**
     * What to call a file of this type. Lives here, next to the magic bytes it goes with.
     * <p>
     * Reached only through {@link com.mettyoung.creditcardapplication.document.Documents#contentFor}, so it
     * has no live caller until FR5 names a file in a multipart body.
     */
    public String fileExtension() {
        return switch (this) {
            case JPEG -> ".jpg";
            case PNG -> ".png";
            case PDF -> ".pdf";
        };
    }

    /** True when {@code head} starts with this type's signature. */
    public boolean matchesMagicBytes(byte[] head) {
        if (head == null || head.length < magic.length) {
            return false;
        }
        return Arrays.equals(head, 0, magic.length, magic, 0, magic.length);
    }
}
