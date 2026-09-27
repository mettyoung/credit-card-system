package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/** The client edited a copy older than what is stored, or lost a race with a concurrent writer. */
class VersionMismatchException extends DomainException {

    private final long currentVersion;

    public VersionMismatchException(long currentVersion) {
        super(Category.CONFLICTING_STATE, "The application was modified. Reload it and retry.");
        this.currentVersion = currentVersion;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("currentVersion", currentVersion);
    }

    public long currentVersion() {
        return currentVersion;
    }
}
