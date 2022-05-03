package com.tyron.builder.api.internal.resources;

import com.google.common.io.Files;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.api.resources.internal.TextResourceInternal;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.internal.resource.ResourceExceptions;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;

public class FileCollectionBackedTextResource implements TextResourceInternal {
    private final TemporaryFileProvider tempFileProvider;
    private final FileCollection fileCollection;
    private final Charset charset;

    public FileCollectionBackedTextResource(TemporaryFileProvider tempFileProvider, FileCollection fileCollection, Charset charset) {
        this.tempFileProvider = tempFileProvider;
        this.fileCollection = fileCollection;
        this.charset = charset;
    }

    @Override
    public String getDisplayName() {
        return fileCollection.toString();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public String asString() {
        File file = asFile();
        try {
            return Files.asCharSource(file, charset).read();
        } catch (FileNotFoundException e) {
            throw ResourceExceptions.readMissing(file, e);
        } catch (IOException e) {
            throw ResourceExceptions.readFailed(file, e);
        }
    }

    @Override
    public Reader asReader() {
        File file = asFile();
        try {
            return Files.newReader(asFile(), charset);
        } catch (FileNotFoundException e) {
            throw ResourceExceptions.readMissing(file, e);
        }
    }

    @Override
    public File asFile(String targetCharset) {
        try {
            Charset targetCharsetObj = Charset.forName(targetCharset);

            if (targetCharsetObj.equals(charset)) {
                return fileCollection.getSingleFile();
            }

            File targetFile = tempFileProvider.createTemporaryFile("fileCollection", ".txt", "resource");
            try {
                Files.asCharSource(fileCollection.getSingleFile(), charset).copyTo(Files.asCharSink(targetFile, targetCharsetObj));
            } catch (IOException e) {
                throw new ResourceException("Could not write " + getDisplayName() + " content to " + targetFile + ".", e);
            }
            return targetFile;
        } catch (Exception e) {
            throw ResourceExceptions.readFailed(getDisplayName(), e);
        }
    }

    @Override
    public File asFile() {
        return asFile(Charset.defaultCharset().name());
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return fileCollection.getBuildDependencies();
    }

    @Override
    public Object getInputProperties() {
        return charset.name();
    }

    @Override
    public FileCollection getInputFiles() {
        return fileCollection;
    }
}
