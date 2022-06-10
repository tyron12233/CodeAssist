package org.gradle.cache.internal;

import org.gradle.cache.PersistentCache;

import java.io.Closeable;

public interface ReferencablePersistentCache extends PersistentCache, Closeable {

    @Override
    void close();

    ReferencablePersistentCache open();
}