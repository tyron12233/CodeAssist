package org.gradle.api.internal.resources;

import com.google.common.io.CharSource;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.tasks.TaskDependencyInternal;
import org.gradle.api.resources.internal.TextResourceInternal;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.resource.ResourceExceptions;

import java.io.File;
import java.io.IOException;
import java.io.Reader;

public class CharSourceBackedTextResource implements TextResourceInternal {

    private final String displayName;
    private final CharSource charSource;

    public CharSourceBackedTextResource(String displayName, CharSource charSource) {
        this.displayName = displayName;
        this.charSource = charSource;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public String asString() {
        try {
            return charSource.read();
        } catch (IOException e) {
            throw ResourceExceptions.readFailed(displayName, e);
        }
    }

    @Override
    public Reader asReader() {
        try {
            return charSource.openStream();
        } catch (IOException e) {
            throw ResourceExceptions.readFailed(displayName, e);
        }
    }

    @Override
    public File asFile(String charset) {
        throw new UnsupportedOperationException("Cannot create file for char source " + charSource);
    }

    @Override
    public File asFile() {
        throw new UnsupportedOperationException("Cannot create file for char source " + charSource);
    }

    @Override
    public Object getInputProperties() {
        return null;
    }

    @Override
    public FileCollection getInputFiles() {
        return null;
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return TaskDependencyInternal.EMPTY;
    }
}
