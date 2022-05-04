package com.tyron.builder.api.internal.resources;

import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.resources.internal.LocalResourceAdapter;
import com.tyron.builder.api.resources.internal.ReadableResourceInternal;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.resource.local.LocalFileStandInExternalResource;

public class DefaultResourceResolver implements ResourceResolver {
    private final FileResolver fileResolver;
    private final FileSystem fileSystem;

    public DefaultResourceResolver(FileResolver fileResolver, FileSystem fileSystem) {
        this.fileResolver = fileResolver;
        this.fileSystem = fileSystem;
    }

    @Override
    public ReadableResourceInternal resolveResource(Object path) {
        if (path instanceof ReadableResourceInternal) {
            return (ReadableResourceInternal) path;
        }
        return new LocalResourceAdapter(new LocalFileStandInExternalResource(fileResolver.resolve(path), fileSystem));
    }
}
