package com.tyron.builder.api.file;

import com.tyron.builder.api.provider.Provider;

/**
 * Provides lazy access to the contents of a given file.
 *
 * @since 6.1
 */
public interface FileContents {

    /**
     * Gets a provider of the entire file contents as a single String.
     *
     * <p>
     * The file is read only once and only when the value is requested for the first time.
     * </p>
     * <p>
     * The returned provider won't have a value, i.e., {@link Provider#isPresent} will return {@code false} when:
     * </p>
     * <ul>
     *     <li>the underlying file does not exist;</li>
     *     <li>this {@link FileContents} is connected to a {@link Provider}{@code <}{@link RegularFile}{@code >} with no value;</li>
     * </ul>
     * <p>
     *     When the underlying file exists but reading it fails, the ensuing exception is permanently propagated to callers of
     *     {@link Provider#get}, {@link Provider#getOrElse}, {@link Provider#getOrNull} and {@link Provider#isPresent}.
     * </p>
     *
     * @return provider of the entire file contents as a single String.
     */
    Provider<String> getAsText();

    /**
     * Gets a provider of the entire file contents as a single byte array.
     *
     * <p>
     * The file is read only once and only when the value is requested for the first time.
     * </p>
     * <p>
     * The returned provider won't have a value, i.e., {@link Provider#isPresent} will return {@code false} when:
     * </p>
     * <ul>
     *     <li>the underlying file does not exist;</li>
     *     <li>this {@link FileContents} is connected to a {@link Provider}{@code <}{@link RegularFile}{@code >} with no value;</li>
     * </ul>
     * <p>
     *     When the underlying file exists but reading it fails, the ensuing exception is permanently propagated to callers of
     *     {@link Provider#get}, {@link Provider#getOrElse}, {@link Provider#getOrNull} and {@link Provider#isPresent}.
     * </p>
     *
     * @return provider of the entire file contents as a single byte array.
     */
    Provider<byte[]> getAsBytes();
}
