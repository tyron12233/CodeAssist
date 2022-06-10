package org.gradle.api.internal.resources;

import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.resources.internal.LocalResourceAdapter;
import org.gradle.api.resources.internal.ReadableResourceInternal;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.resource.local.LocalFileStandInExternalResource;

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
