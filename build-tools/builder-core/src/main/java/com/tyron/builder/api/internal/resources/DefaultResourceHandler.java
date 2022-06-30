package com.tyron.builder.api.internal.resources;

import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.resources.ResourceHandler;
import com.tyron.builder.api.resources.TextResourceFactory;
import com.tyron.builder.api.resources.internal.ReadableResourceInternal;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;

public class DefaultResourceHandler implements ResourceHandler {
    private final ResourceResolver resourceResolver;
    private final TextResourceFactory textResourceFactory;

    private DefaultResourceHandler(ResourceResolver resourceResolver, TextResourceFactory textResourceFactory) {
        this.resourceResolver = resourceResolver;
        this.textResourceFactory = textResourceFactory;
    }

    @Override
    public ReadableResourceInternal gzip(Object path) {
//        return new GzipArchiver(resourceResolver.resolveResource(path));
        throw new UnsupportedOperationException();
    }

    @Override
    public ReadableResourceInternal bzip2(Object path) {
//        return new Bzip2Archiver(resourceResolver.resolveResource(path));
        throw new UnsupportedOperationException();
    }

    @Override
    public TextResourceFactory getText() {
        return textResourceFactory;
    }

    public interface Factory {
        ResourceHandler create(FileOperations fileOperations);

        static Factory from(FileResolver fileResolver, FileSystem fileSystem, TemporaryFileProvider tempFileProvider, ApiTextResourceAdapter.Factory textResourceAdapterFactory) {
            return new FactoryImpl(fileResolver, fileSystem, tempFileProvider, textResourceAdapterFactory);
        }

        class FactoryImpl implements Factory {
            private final FileResolver fileResolver;
            private final FileSystem fileSystem;
            private final TemporaryFileProvider tempFileProvider;
            private final ApiTextResourceAdapter.Factory textResourceAdapterFactory;

            public FactoryImpl(FileResolver fileResolver, FileSystem fileSystem, TemporaryFileProvider tempFileProvider, ApiTextResourceAdapter.Factory textResourceAdapterFactory) {
                this.fileResolver = fileResolver;
                this.fileSystem = fileSystem;
                this.tempFileProvider = tempFileProvider;
                this.textResourceAdapterFactory = textResourceAdapterFactory;
            }

            public DefaultResourceHandler create(FileOperations fileOperations) {
                ResourceResolver resourceResolver = new DefaultResourceResolver(fileResolver, fileSystem);
                DefaultTextResourceFactory textResourceFactory = new DefaultTextResourceFactory(fileOperations, tempFileProvider, textResourceAdapterFactory);
                return new DefaultResourceHandler(resourceResolver, textResourceFactory);
            }
        }
    }
}
