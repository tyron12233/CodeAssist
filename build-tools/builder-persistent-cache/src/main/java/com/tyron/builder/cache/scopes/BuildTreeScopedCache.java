package com.tyron.builder.cache.scopes;

/**
 * Factory for creating build tree scoped caches. These typically live under the ~/.gradle directory of the root build
 * in the build tree.
 */
public interface BuildTreeScopedCache extends ScopedCache {
}