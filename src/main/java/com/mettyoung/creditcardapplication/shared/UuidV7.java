package com.mettyoung.creditcardapplication.shared;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 version 7 UUID: 48-bit Unix epoch milliseconds followed by 74 random bits.
 * Time-ordered (newest-first sorting, B-tree insert locality) while staying unguessable.
 * Ordering within the same millisecond is arbitrary.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long unixMillis = System.currentTimeMillis();
        long mostSignificant = (unixMillis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
