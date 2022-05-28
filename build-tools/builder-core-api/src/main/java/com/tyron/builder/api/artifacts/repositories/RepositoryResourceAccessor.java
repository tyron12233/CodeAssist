package com.tyron.builder.api.artifacts.repositories;

import com.tyron.builder.api.Action;

import java.io.InputStream;

/**
 * Provides access to resources on an artifact repository. Gradle takes care of caching
 * the resources locally. The scope of the cache may depend on the accessor: users should
 * refer to the javadocs of the methods providing an accessor to determine the scope.
 *
 * @since 4.0
 */
public interface RepositoryResourceAccessor {
    /**
     * Perform an action on the contents of a remote resource.
     * @param relativePath path to the resource, relative to the base URI of the repository
     * @param action action to execute on the resource
     */
    void withResource(String relativePath, Action<? super InputStream> action);
}
