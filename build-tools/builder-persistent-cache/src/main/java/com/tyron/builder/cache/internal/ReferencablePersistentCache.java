package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.PersistentCache;

import java.io.Closeable;

public interface ReferencablePersistentCache extends PersistentCache, Closeable {

    @Override
    void close();

    ReferencablePersistentCache open();
}