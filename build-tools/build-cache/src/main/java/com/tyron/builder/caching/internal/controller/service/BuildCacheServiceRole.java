package com.tyron.builder.caching.internal.controller.service;

import com.tyron.builder.internal.scan.UsedByScanPlugin;

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
