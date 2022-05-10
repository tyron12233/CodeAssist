package com.tyron.builder.internal.resource;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;

/**
 * Represents a binary resource and provides access to the content and meta-data of the resource. The resource may or may not exist, and may change over time.
 */
public interface ExternalResource extends Resource {
    /**
     * Get the URI of the resource.
     */
    URI getURI();

    /**
     * Copies the contents of this resource to the given file.
     *
     * @throws ResourceException on failure to copy the content.
     * @throws org.gradle.api.resources.MissingResourceException when the resource does not exist
     */
    ExternalResourceReadResult<Void> writeTo(File destination) throws ResourceException;

    /**
     * Copies the contents of this resource to the given file, if the resource exists.
     *
     * @return null if this resource does not exist.
     * @throws ResourceException on failure to copy the content.
     */
    @Nullable
    ExternalResourceReadResult<Void> writeToIfPresent(File destination) throws ResourceException;

    /**
     * Copies the binary contents of this resource to the given stream. Does not close the provided stream.
     *
     * @throws ResourceException on failure to copy the content.
     * @throws org.gradle.api.resources.MissingResourceException when the resource does not exist
     */
    ExternalResourceReadResult<Void> writeTo(OutputStream destination) throws ResourceException;

    /**
     * Executes the given action against the binary contents of this resource.
     *
     * @throws ResourceException on failure to read the content.
     * @throws org.gradle.api.resources.MissingResourceException when the resource does not exist
     */
    @SuppressWarnings("overloads")
    ExternalResourceReadResult<Void> withContent(Action<? super InputStream> readAction) throws ResourceException;

    /**
     * Executes the given action against the binary contents of this resource.
     *
     * @throws ResourceException on failure to read the content.
     * @throws org.gradle.api.resources.MissingResourceException when the resource does not exist
     */
    @SuppressWarnings("overloads")
    <T> ExternalResourceReadResult<T> withContent(ContentAction<? extends T> readAction) throws ResourceException;

    /**
     * Executes the given action against the binary contents of this resource, if the resource exists.
     *
     * @return null if the resource does not exist.
     * @throws ResourceException on failure to read the content.
     */
    @Nullable
    <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAction<? extends T> readAction) throws ResourceException;

    /**
     * Executes the given action against the binary contents and meta-data of this resource.
     * Generally, this method will be less efficient than one of the other {@code withContent} methods that do
     * not provide the meta-data, as additional requests may need to be made to obtain the meta-data.
     *
     * @throws ResourceException on failure to read the content.
     * @throws org.gradle.api.resources.MissingResourceException when the resource does not exist
     */
    <T> ExternalResourceReadResult<T> withContent(ContentAndMetadataAction<? extends T> readAction) throws ResourceException;

    /**
     * Executes the given action against the binary contents and meta-data of this resource.
     * Generally, this method will be less efficient than one of the other {@code withContent} methods that do
     * not provide the meta-data, as additional requests may need to be made to obtain the meta-data.
     *
     * @return null if the resource does not exist.
     * @throws ResourceException on failure to read the content.
     */
    @Nullable
    <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAndMetadataAction<? extends T> readAction) throws ResourceException;

    /**
     * Copies the given content to this resource.
     *
     * @param source The local resource to be transferred.
     * @throws ResourceException On failure to write the content.
     */
    ExternalResourceWriteResult put(ReadableContent source) throws ResourceException;

    /**
     * Return a listing of child resources names.
     *
     * @return A listing of the direct children of the given parent. Returns null when the parent resource does not exist.
     * @throws ResourceException On listing failure.
     */
    @Nullable
    List<String> list() throws ResourceException;

    /**
     * Returns the meta-data for this resource, if the resource exists.
     *
     * @return null when the resource does not exist.
     */
    @Nullable
    ExternalResourceMetaData getMetaData();

    interface ContentAndMetadataAction<T> {
        T execute(InputStream inputStream, ExternalResourceMetaData metaData) throws IOException;
    }

    interface ContentAction<T> {
        T execute(InputStream inputStream) throws IOException;
    }
}
