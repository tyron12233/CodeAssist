package com.tyron.builder.api.resources;

import com.google.common.io.Files;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.tasks.TaskDependencyInternal;
import com.tyron.builder.api.resources.internal.TextResourceInternal;
import com.tyron.builder.api.tasks.TaskDependency;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;

public class StringBackedTextResource implements TextResourceInternal {
    private final TemporaryFileProvider tempFileProvider;
    private final String string;

    public StringBackedTextResource(TemporaryFileProvider tempFileProvider, String string) {
        this.tempFileProvider = tempFileProvider;
        this.string = string;
    }

    @Override
    public String getDisplayName() {
        return "text resource";
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public String asString() {
        return string;
    }

    @Override
    public Reader asReader() {
        return new StringReader(string);
    }

    @Override
    public File asFile(String charset) {
        File file = tempFileProvider.createTemporaryFile("string", ".txt", "resource");
        try {
            Files.asCharSink(file, Charset.forName(charset)).write(string);
        } catch (IOException e) {
            throw new ResourceException("Could not write " + getDisplayName() + " content to " + file + ".", e);
        }
        return file;
    }

    @Override
    public File asFile() {
        return asFile(Charset.defaultCharset().name());
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return TaskDependencyInternal.EMPTY;
    }

    @Override
    public Object getInputProperties() {
        return string;
    }

    @Override
    public FileCollection getInputFiles() {
        return null;
    }
}
