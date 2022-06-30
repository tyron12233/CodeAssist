package com.tyron.builder.caching.internal;

import com.tyron.builder.internal.file.TreeType;

import java.io.File;

/**
 * An entity that can potentially be stored in the build cache.
 */
public interface CacheableEntity {
    /**
     * The identity of the work as a part of the build to be reported in the origin metadata.
     */
    String getIdentity();

    /**
     * The type of the work to report in the origin metadata.
     */
    Class<?> getType();

    String getDisplayName();

    void visitOutputTrees(CacheableTreeVisitor visitor);

    @FunctionalInterface
    interface CacheableTreeVisitor {
        void visitOutputTree(String name, TreeType type, File root);
    }
}