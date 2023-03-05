package org.gradle.caching.internal.controller.service;

import org.gradle.internal.scan.UsedByScanPlugin;

@UsedByScanPlugin("values are expected (type is not linked), see BuildCacheStoreBuildOperationType and friends")
public enum BuildCacheServiceRole {
    LOCAL,
    REMOTE;

    private final String displayName;

    BuildCacheServiceRole() {
        this.displayName = name().toLowerCase();
    }

    public String getDisplayName() {
        return displayName;
    }
}
