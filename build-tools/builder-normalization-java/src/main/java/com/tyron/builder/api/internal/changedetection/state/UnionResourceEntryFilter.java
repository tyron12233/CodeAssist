package com.tyron.builder.api.internal.changedetection.state;

import com.google.common.hash.Hasher;

import java.nio.charset.StandardCharsets;
import java.util.List;

import java.util.Collection;

/**
 * This represents a composite resource entry filter which ignores the entry if any of the filters would ignore the entry.
 */
public class UnionResourceEntryFilter implements ResourceEntryFilter {
    private final Collection<ResourceEntryFilter> filters;

    public UnionResourceEntryFilter(Collection<ResourceEntryFilter> filters) {
        this.filters = filters;
    }

    @Override
    public boolean shouldBeIgnored(String entry) {
        return filters.stream().anyMatch(resourceEntryFilter -> resourceEntryFilter.shouldBeIgnored(entry));
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        filters.forEach(resourceEntryFilter -> resourceEntryFilter.appendConfigurationToHasher(hasher));
    }
}