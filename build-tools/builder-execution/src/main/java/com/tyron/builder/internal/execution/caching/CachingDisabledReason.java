package com.tyron.builder.internal.execution.caching;

public class CachingDisabledReason {
    private final CachingDisabledReasonCategory category;
    private final String message;

    public CachingDisabledReason(CachingDisabledReasonCategory category, String message) {
        this.category = category;
        this.message = message;
    }

    public CachingDisabledReasonCategory getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return String.format("%s (%s)", message, category);
    }
}