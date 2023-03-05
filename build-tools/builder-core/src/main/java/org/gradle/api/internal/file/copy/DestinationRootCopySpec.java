package org.gradle.api.internal.file.copy;

import org.gradle.api.file.CopySpec;
import org.gradle.internal.file.PathToFileResolver;

import javax.inject.Inject;
import java.io.File;

public class DestinationRootCopySpec extends DelegatingCopySpecInternal {

    private final PathToFileResolver fileResolver;
    private final CopySpecInternal delegate;

    private Object destinationDir;

    @Inject
    public DestinationRootCopySpec(PathToFileResolver fileResolver, CopySpecInternal delegate) {
        this.fileResolver = fileResolver;
        this.delegate = delegate;
    }

    @Override
    protected CopySpecInternal getDelegateCopySpec() {
        return delegate;
    }

    @Override
    public CopySpec into(Object destinationDir) {
        this.destinationDir = destinationDir;
        return this;
    }

    public File getDestinationDir() {
        return destinationDir == null ? null : fileResolver.resolve(destinationDir);
    }

    // TODO:configuration-cache - remove this
    public CopySpecInternal getDelegate() {
        return delegate;
    }
}
