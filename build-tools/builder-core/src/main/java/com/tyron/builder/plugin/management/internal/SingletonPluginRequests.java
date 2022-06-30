package com.tyron.builder.plugin.management.internal;

import com.google.common.collect.Iterators;

import java.util.Iterator;

public class SingletonPluginRequests implements PluginRequests {
    private final PluginRequestInternal requestInternal;

    public SingletonPluginRequests(PluginRequestInternal requestInternal) {
        this.requestInternal = requestInternal;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public Iterator<PluginRequestInternal> iterator() {
        return Iterators.singletonIterator(requestInternal);
    }
}
