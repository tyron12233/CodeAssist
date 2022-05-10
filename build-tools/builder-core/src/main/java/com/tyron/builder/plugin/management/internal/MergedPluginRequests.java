package com.tyron.builder.plugin.management.internal;

import com.google.common.collect.Iterators;

import java.util.Iterator;

public class MergedPluginRequests implements PluginRequests {

    private final PluginRequests first;
    private final PluginRequests second;

    public MergedPluginRequests(PluginRequests first, PluginRequests second) {
        if (first.isEmpty() || second.isEmpty()) {
            throw new IllegalStateException("requests must not be empty");
        }
        this.first = first;
        this.second = second;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public Iterator<PluginRequestInternal> iterator() {
        return Iterators.concat(first.iterator(), second.iterator());
    }
}
