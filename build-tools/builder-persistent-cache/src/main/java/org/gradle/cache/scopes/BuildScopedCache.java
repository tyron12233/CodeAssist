package org.gradle.cache.scopes;


/**
 * Factory for creating build scoped caches. These typically live under the ~/.gradle directory of the build.
 */
public interface BuildScopedCache extends ScopedCache {
}
