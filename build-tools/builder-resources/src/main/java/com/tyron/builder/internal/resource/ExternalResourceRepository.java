package com.tyron.builder.internal.resource;

/**
 * Provides access to {@link ExternalResource} implementations, given a URI or resource name.
 */
public interface ExternalResourceRepository {
    /**
     * Returns a copy of this repository with progress logging enabled.
     */
    ExternalResourceRepository withProgressLogging();

    /**
     * Returns the resource with the given name. Note that this method does not access the resource in any way, it simply creates an object that can. To access the resource, use the methods on the returned object.
     *
     * @param resource The location of the resource
     * @param revalidate Ensure the external resource is not stale when reading its content
     */
    ExternalResource resource(ExternalResourceName resource, boolean revalidate);

    /**
     * Returns the resource with the given name. Note that this method does not access the resource in any way, it simply creates an object that can. To access the resource, use the methods on the returned object.
     *
     * @param resource The location of the resource
     */
    ExternalResource resource(ExternalResourceName resource);
}
