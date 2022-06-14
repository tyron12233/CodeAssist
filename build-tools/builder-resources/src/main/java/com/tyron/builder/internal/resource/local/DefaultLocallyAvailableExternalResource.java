package com.tyron.builder.internal.resource.local;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.resource.ExternalResourceReadResult;
import com.tyron.builder.internal.resource.ExternalResourceWriteResult;
import com.tyron.builder.internal.resource.ReadableContent;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import javax.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;

/**
 * A {@link LocallyAvailableExternalResource} implementation that represents a local file backed copy of some remote resource.
 */
public class DefaultLocallyAvailableExternalResource implements LocallyAvailableExternalResource {
    private final URI source;
    private final ExternalResourceMetaData metaData;
    private final LocallyAvailableExternalResource localFile;

    public DefaultLocallyAvailableExternalResource(URI source, File locallyAvailableResource, ExternalResourceMetaData metaData, FileSystem fileSystem) {
        localFile = new LocalFileStandInExternalResource(locallyAvailableResource, fileSystem);
        this.source = source;
        this.metaData = metaData;
    }

    @Override
    public String getDisplayName() {
        return source.toString();
    }

    @Override
    public URI getURI() {
        return source;
    }

    @Nullable
    @Override
    public ExternalResourceMetaData getMetaData() {
        return metaData;
    }

    @Override
    public File getFile() {
        return localFile.getFile();
    }

    @Nullable
    @Override
    public List<String> list() throws ResourceException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExternalResourceWriteResult put(ReadableContent source) throws ResourceException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExternalResourceReadResult<Void> writeTo(File destination) throws ResourceException {
        return localFile.writeTo(destination);
    }

    @Override
    @Nullable
    public ExternalResourceReadResult<Void> writeToIfPresent(File destination) throws ResourceException {
        return localFile.writeToIfPresent(destination);
    }

    @Override
    public ExternalResourceReadResult<Void> writeTo(OutputStream destination) throws ResourceException {
        return localFile.writeTo(destination);
    }

    @Override
    public ExternalResourceReadResult<Void> withContent(Action<? super InputStream> readAction) throws ResourceException {
        return localFile.withContent(readAction);
    }

    @Override
    public <T> ExternalResourceReadResult<T> withContent(ContentAction<? extends T> readAction) throws ResourceException {
        return localFile.withContent(readAction);
    }

    @Override
    @Nullable
    public <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAction<? extends T> readAction) throws ResourceException {
        return localFile.withContentIfPresent(readAction);
    }

    @Override
    public <T> ExternalResourceReadResult<T> withContent(ContentAndMetadataAction<? extends T> readAction) throws ResourceException {
        return localFile.withContent(readAction);
    }

    @Override
    @Nullable
    public <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAndMetadataAction<? extends T> readAction) throws ResourceException {
        return localFile.withContentIfPresent(readAction);
    }
}
