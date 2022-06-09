package com.tyron.builder.api.internal.file.archive.compression;

import com.tyron.builder.api.resources.internal.ReadableResourceInternal;

import java.io.File;
import java.io.InputStream;
import java.net.URI;

abstract class AbstractArchiver implements CompressedReadableResource {
    protected final ReadableResourceInternal resource;
    protected final URI uri;

    public AbstractArchiver(ReadableResourceInternal resource) {
        assert resource != null;
        this.uri = new URIBuilder(resource.getURI()).schemePrefix(getSchemePrefix()).build();
        this.resource = resource;
    }

    abstract protected String getSchemePrefix();

    @Override
    public abstract InputStream read();

    @Override
    public String getDisplayName() {
        return resource.getDisplayName();
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public String getBaseName() {
        return resource.getBaseName();
    }

    @Override
    public File getBackingFile() {
        return resource.getBackingFile();
    }
}
