package com.tyron.builder.internal.resource.local;

import com.google.common.io.CountingInputStream;
import com.google.common.io.CountingOutputStream;
import com.google.common.io.Files;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.resource.AbstractExternalResource;
import com.tyron.builder.internal.resource.ExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceReadResult;
import com.tyron.builder.internal.resource.ExternalResourceWriteResult;
import com.tyron.builder.internal.resource.LocalBinaryResource;
import com.tyron.builder.internal.resource.ReadableContent;
import com.tyron.builder.internal.resource.ResourceExceptions;
import com.tyron.builder.internal.resource.metadata.DefaultExternalResourceMetaData;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import org.apache.commons.io.IOUtils;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * A file backed {@link ExternalResource} implementation.
 */
public class LocalFileStandInExternalResource extends AbstractExternalResource implements LocallyAvailableExternalResource, LocalBinaryResource {
    private final File localFile;
    private final FileSystem fileSystem;

    public LocalFileStandInExternalResource(File localFile, FileSystem fileSystem) {
        this.localFile = localFile;
        this.fileSystem = fileSystem;
    }

    @Override
    public URI getURI() {
        return localFile.toURI();
    }

    @Override
    public File getFile() {
        return localFile;
    }

    @Override
    public String getBaseName() {
        return localFile.getName();
    }

    @Override
    public File getContainingFile() {
        return localFile;
    }

    @Override
    public long getContentLength() {
        return localFile.length();
    }

    @Override
    public String getDisplayName() {
        return localFile.getPath();
    }

    @Override
    @Nullable
    public ExternalResourceMetaData getMetaData() {
        FileMetadata fileMetadata = fileSystem.stat(localFile);
        if (fileMetadata.getType() == FileType.Missing) {
            return null;
        }
        return new DefaultExternalResourceMetaData(localFile.toURI(), fileMetadata.getLastModified(), fileMetadata.getLength());
    }

    @Override
    public ExternalResourceReadResult<Void> writeTo(OutputStream output) {
        if (!localFile.exists()) {
            throw ResourceExceptions.getMissing(getURI());
        }
        try {
            CountingInputStream input = new CountingInputStream(new FileInputStream(localFile));
            try {
                IOUtils.copyLarge(input, output);
            } finally {
                input.close();
            }
            return ExternalResourceReadResult.of(input.getCount());
        } catch (IOException e) {
            throw ResourceExceptions.getFailed(getURI(), e);
        }
    }

    @Override
    @Nullable
    public ExternalResourceReadResult<Void> writeToIfPresent(File destination) {
        if (!localFile.exists()) {
            return null;
        }
        try {
            CountingInputStream input = new CountingInputStream(new FileInputStream(localFile));
            try {
                try (FileOutputStream output = new FileOutputStream(destination)) {
                    IOUtils.copyLarge(input, output);
                }
            } finally {
                input.close();
            }
            return ExternalResourceReadResult.of(input.getCount());
        } catch (IOException e) {
            throw ResourceExceptions.getFailed(getURI(), e);
        }
    }

    @Override
    public ExternalResourceReadResult<Void> withContent(Action<? super InputStream> readAction) {
        if (!localFile.exists()) {
            throw ResourceExceptions.getMissing(getURI());
        }
        try {
            CountingInputStream input = new CountingInputStream(new BufferedInputStream(new FileInputStream(localFile)));
            try {
                readAction.execute(input);
            } finally {
                input.close();
            }
            return ExternalResourceReadResult.of(input.getCount());
        } catch (IOException e) {
            throw ResourceExceptions.getFailed(getURI(), e);
        }
    }

    @Nullable
    @Override
    public <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAndMetadataAction<? extends T> readAction) throws ResourceException {
        if (!localFile.exists()) {
            return null;
        }
        try {
            try (CountingInputStream input = new CountingInputStream(new BufferedInputStream(new FileInputStream(localFile)))) {
                T resourceReadResult = readAction.execute(input, getMetaData());
                return ExternalResourceReadResult.of(input.getCount(), resourceReadResult);
            }
        } catch (IOException e) {
            throw ResourceExceptions.getFailed(getURI(), e);
        }
    }

    @Nullable
    @Override
    public <T> ExternalResourceReadResult<T> withContentIfPresent(ContentAction<? extends T> readAction) throws ResourceException {
        if (!localFile.exists()) {
            return null;
        }
        try {
            try (CountingInputStream input = new CountingInputStream(new BufferedInputStream(new FileInputStream(localFile)))) {
                T resourceReadResult = readAction.execute(input);
                return ExternalResourceReadResult.of(input.getCount(), resourceReadResult);
            }
        } catch (IOException e) {
            throw ResourceExceptions.getFailed(getURI(), e);
        }
    }

    @Override
    public ExternalResourceWriteResult put(ReadableContent location) {
        try {
            if (!localFile.canWrite()) {
                localFile.delete();
            }
            Files.createParentDirs(localFile);

            try (InputStream input = location.open()) {
                CountingOutputStream output = new CountingOutputStream(new FileOutputStream(localFile));
                try {
                    IOUtils.copyLarge(input, output);
                } finally {
                    output.close();
                }
                return new ExternalResourceWriteResult(output.getCount());
            }
        } catch (IOException e) {
            throw ResourceExceptions.putFailed(getURI(), e);
        }
    }

    @Override
    public InputStream open() throws ResourceException {
        if (localFile.isDirectory()) {
            throw ResourceExceptions.readFolder(localFile);
        }
        try {
            return new FileInputStream(localFile);
        } catch (FileNotFoundException e) {
            throw ResourceExceptions.readMissing(localFile, e);
        }
    }

    @Nullable
    @Override
    public List<String> list() throws ResourceException {
        if (localFile.isDirectory()) {
            String[] names = localFile.list();
            return Arrays.asList(names);
        }
        return null;
    }
}
