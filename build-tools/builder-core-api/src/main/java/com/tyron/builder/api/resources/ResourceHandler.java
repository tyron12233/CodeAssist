package com.tyron.builder.api.resources;

/**
 * Provides access to resource-specific utility methods, for example factory methods that create various resources.
 */
public interface ResourceHandler {

    /**
     * Creates resource that points to a gzip compressed file at the given path.
     * The path is evaluated as per {@link org.gradle.api.Project#file(Object)}.
     *
     * @param path The path evaluated as per {@link org.gradle.api.Project#file(Object)}.
     */
    ReadableResource gzip(Object path);

    /**
     * Creates resource that points to a bzip2 compressed file at the given path.
     * The path is evaluated as per {@link org.gradle.api.Project#file(Object)}.
     *
     * @param path The path evaluated as per {@link org.gradle.api.Project#file(Object)}.
     */
    ReadableResource bzip2(Object path);

    /**
     * Returns a factory for creating {@code TextResource}s from various sources such as
     * strings, files, and archive entries.
     *
     * @since 2.2
     *
     * @return a factory for creating {@code TextResource}s
     */
    TextResourceFactory getText();
}