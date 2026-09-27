package com.mettyoung.creditcardapplication.application;

/**
 * Stored as {@code text} with {@code @Enumerated(STRING)}, so adding a constant needs no migration. Ordinals
 * would break on a reorder and a Postgres enum type would make every new state a schema change.
 */
public enum ApplicationStatus {
    /** Declared data is editable. The only state FR1 has; later increments add the rest. */
    DRAFT
}
