package com.tyron.builder.internal.resource.local;

import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ExternalResourceRepository;
import com.tyron.builder.internal.resource.LocalBinaryResource;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import java.io.File;
import java.net.URI;

public class FileResourceConnector implements FileResourceRepository {
    private final FileSystem fileSystem;

    public FileResourceConnector(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @Override
    public ExternalResourceRepository withProgressLogging() {
        return this;
    }

    @Override
    public LocalBinaryResource localResource(File file) {
        return new LocalFileStandInExternalResource(file, fileSystem);
    }

    @Override
    public LocallyAvailableExternalResource resource(ExternalResourceName resource, boolean revalidate) {
        return resource(resource);
    }

    @Override
    public LocallyAvailableExternalResource resource(ExternalResourceName location) {
        File localFile = getFile(location);
        return new LocalFileStandInExternalResource(localFile, fileSystem);
    }

    @Override
    public LocallyAvailableExternalResource resource(File file) {
        return new LocalFileStandInExternalResource(file, fileSystem);
    }

    @Override
    public LocallyAvailableExternalResource resource(File file, URI originUri, ExternalResourceMetaData originMetadata) {
        return new DefaultLocallyAvailableExternalResource(originUri, file, originMetadata, fileSystem);
    }

    private static File getFile(ExternalResourceName location) {
        return new File(location.getUri());
    }
}
