package com.tyron.builder.internal.resource.transfer;

import com.google.common.io.CountingInputStream;
import org.apache.commons.io.IOUtils;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.resource.AbstractExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ExternalResourceReadResult;
import com.tyron.builder.internal.resource.ExternalResourceWriteResult;
import com.tyron.builder.internal.resource.ReadableContent;
import com.tyron.builder.internal.resource.ResourceExceptions;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;

public class AccessorBackedExternalResource extends AbstractExternalResource {
    private final ExternalResourceName name;
    private final ExternalResourceAccessor accessor;
    private final ExternalResourceUploader uploader;
    private final ExternalResourceLister lister;
    // Should really be a parameter to the 'withContent' methods or baked into the accessor
    private final boolean revalidate;

    public AccessorBackedExternalResource(ExternalResourceName name, ExternalResourceAccessor accessor, ExternalResourceUploader uploader, ExternalResourceLister lister, boolean revalidate) {
        this.name = name;
        this.accessor = accessor;
        this.uploader = uploader;
        this.lister = lister;
        this.revalidate = revalidate;
    }

    @Override
    public URI getURI() {
        return name.getUri();
    }

    @Override
    public String getDisplayName() {
        return name.getDisplayName();
    }

    @Nullable
    @Override
    public ExternalResourceReadResult<Void> writeToIfPresent(File destination) throws ResourceException {
        return accessor.withContent(name, revalidate, inputStream -> {
            try (CountingInputStream input = new CountingInputStream(inputStream)) {
                try (FileOutputStream output = new FileOutputStream(destination)) {
                    IOUtils.copyLarge(input, output);
                    return ExternalResourceReadResult.of(input.getCount());
                }
            }
        });
    }

    @Override
    public ExternalResourceReadResult<Void> writeTo(OutputStream destination) throws ResourceException {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAction<? extends T> readAction) throws ResourceException {
        return accessor.withContent(name, revalidate, inputStream -> {
            try (CountingInputStream input = new CountingInputStream(new BufferedInputStream(inputStream))) {
                T value = readAction.execute(input);
                return ExternalResourceReadResult.of(input.getCount(), value);
            }
        });
    }

    @Nullable
    @Override
    public <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAndMetadataAction<? extends T> readAction) throws ResourceException {
        return accessor.withContent(name, revalidate, (inputStream, metadata) -> {
            try (CountingInputStream stream = new CountingInputStream(new BufferedInputStream(inputStream))) {
                T value = readAction.execute(stream, metadata);
                return ExternalResourceReadResult.of(stream.getCount(), value);
            }
        });
    }

    @Override
    public ExternalResourceReadResult<Void> withContent(Action<? super InputStream> readAction) throws ResourceException {
        ExternalResourceReadResult<Void> result = accessor.withContent(name, revalidate, inputStream -> {
            CountingInputStream input = new CountingInputStream(inputStream);
            readAction.execute(input);
            return ExternalResourceReadResult.of(input.getCount());
        });
        if (result == null) {
            throw ResourceExceptions.getMissing(name.getUri());
        }
        return result;
    }

    @Override
    public <T> ExternalResourceReadResult<T> withContent(ContentAndMetadataAction<? extends T> readAction) throws ResourceException {
        ExternalResourceReadResult<T> result = withContentIfPresent(readAction);
        if (result == null) {
            throw ResourceExceptions.getMissing(getURI());
        }
        return result;
    }

    @Override
    public ExternalResourceWriteResult put(final ReadableContent source) throws ResourceException {
        try {
            CountingReadableContent countingResource = new CountingReadableContent(source);
            uploader.upload(countingResource, name);
            return new ExternalResourceWriteResult(countingResource.getCount());
        } catch (IOException e) {
            throw ResourceExceptions.putFailed(getURI(), e);
        }
    }

    @Nullable
    @Override
    public List<String> list() throws ResourceException {
        try {
            return lister.list(name);
        } catch (Exception e) {
            throw ResourceExceptions.getFailed(getURI(), e);
        }
    }

    @Override
    public ExternalResourceMetaData getMetaData() {
        return accessor.getMetaData(name, revalidate);
    }

    private static class CountingReadableContent implements ReadableContent {
        private final ReadableContent source;
        private CountingInputStream instr;
        private long count;

        CountingReadableContent(ReadableContent source) {
            this.source = source;
        }

        @Override
        public InputStream open() throws ResourceException {
            if (instr != null) {
                count += instr.getCount();
            }
            instr = new CountingInputStream(source.open());
            return instr;
        }

        @Override
        public long getContentLength() {
            return source.getContentLength();
        }

        public long getCount() {
            return instr != null ? count + instr.getCount() : count;
        }
    }
}
