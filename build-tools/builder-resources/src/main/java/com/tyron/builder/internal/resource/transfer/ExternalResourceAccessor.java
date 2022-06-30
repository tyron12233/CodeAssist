package com.tyron.builder.internal.resource.transfer;

import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.resource.ExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import javax.annotation.Nullable;

/**
 * You should use {@link ExternalResource} instead of this type.
 */
public interface ExternalResourceAccessor {
    /**
     * Reads the resource at the given location.
     *
     * If the resource does not exist, this method should return null.
     *
     * If the resource may exist but can't be accessed due to some configuration issue, the implementation
     * must throw an {@link ResourceException} to indicate a fatal condition.
     *
     * @param location The address of the resource to obtain
     * @param revalidate The resource should be revalidated as part of the request
     * @param action The action to apply to the content of the resource.
     * @return The result of the action if the resource exists, or null if the resource does not exist.
     * @throws ResourceException If the resource may exist, but not could be obtained for some reason.
     */
    @Nullable
    <T> T withContent(ExternalResourceName location, boolean revalidate, ExternalResource.ContentAndMetadataAction<T> action) throws ResourceException;

    /**
     * Reads the resource at the given location.
     *
     * If the resource does not exist, this method should return null.
     *
     * If the resource may exist but can't be accessed due to some configuration issue, the implementation
     * must throw an {@link ResourceException} to indicate a fatal condition.
     *
     * @param location The address of the resource to obtain
     * @param revalidate The resource should be revalidated as part of the request
     * @param action The action to apply to the content of the resource.
     * @return The result of the action if the resource exists, or null if the resource does not exist.
     * @throws ResourceException If the resource may exist, but not could be obtained for some reason.
     */
    @Nullable
    default <T> T withContent(ExternalResourceName location, boolean revalidate, ExternalResource.ContentAction<T> action) throws ResourceException {
        return withContent(location, revalidate, (inputStream, metaData) -> action.execute(inputStream));
    }

    /**
     * Obtains only the metadata about the resource.
     *
     * If it is determined that the resource does not exist, this method should return null.
     *
     * If the resource may exist but can't be accessed due to some configuration issue, the implementation
     * must throw an {@link ResourceException} to indicate a fatal condition.
     *
     * @param location The location of the resource to obtain the metadata for
     * @param revalidate The resource should be revalidated as part of the request
     * @return The available metadata, null if the resource doesn't exist
     * @throws ResourceException If the resource may exist, but not could be obtained for some reason
     */
    @Nullable
    ExternalResourceMetaData getMetaData(ExternalResourceName location, boolean revalidate) throws ResourceException;
}
