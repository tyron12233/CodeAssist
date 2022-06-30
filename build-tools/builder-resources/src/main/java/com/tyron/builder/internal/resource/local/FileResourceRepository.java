package com.tyron.builder.internal.resource.local;

import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ExternalResourceRepository;
import com.tyron.builder.internal.resource.LocalBinaryResource;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import java.io.File;
import java.net.URI;

public interface FileResourceRepository extends ExternalResourceRepository {
    LocalBinaryResource localResource(File file);

    /**
     * Returns the given file as a resource.
     */
    LocallyAvailableExternalResource resource(File file);

    /**
     * Returns the given file as a resource, with the given origin details.
     */
    LocallyAvailableExternalResource resource(File file, URI originUri, ExternalResourceMetaData originMetadata);

    @Override
    LocallyAvailableExternalResource resource(ExternalResourceName resource);

    @Override
    LocallyAvailableExternalResource resource(ExternalResourceName resource, boolean revalidate);
}
