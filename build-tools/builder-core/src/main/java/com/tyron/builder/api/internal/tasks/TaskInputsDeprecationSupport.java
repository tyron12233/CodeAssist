package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.tasks.TaskInputFilePropertyBuilder;
import com.tyron.builder.api.tasks.TaskInputPropertyBuilder;
import com.tyron.builder.api.tasks.TaskInputs;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class TaskInputsDeprecationSupport implements TaskInputs {

    private UnsupportedOperationException failWithUnsupportedMethod(String method) {
        throw new UnsupportedOperationException(String.format("Chaining of the TaskInputs.%s method is not supported since Gradle 5.0.", method));
    }

    @Override
    public boolean getHasInputs() {
        throw failWithUnsupportedMethod("getHasInputs()");
    }

    @Override
    public FileCollection getFiles() {
        throw failWithUnsupportedMethod("getFiles()");
    }

    @Override
    public TaskInputFilePropertyBuilder files(Object... paths) {
        throw failWithUnsupportedMethod("files(Object...)");
    }

    @Override
    public TaskInputFilePropertyBuilder file(Object path) {
        throw failWithUnsupportedMethod("file(Object)");
    }

    @Override
    public TaskInputFilePropertyBuilder dir(Object dirPath) {
        throw failWithUnsupportedMethod("dir(Object)");
    }

    @Override
    public Map<String, Object> getProperties() {
        throw failWithUnsupportedMethod("getProperties()");
    }

    @Override
    public TaskInputPropertyBuilder property(String name, @Nullable Object value) {
        throw failWithUnsupportedMethod("property(String, Object)");
    }

    @Override
    public TaskInputs properties(Map<String, ?> properties) {
        throw failWithUnsupportedMethod("properties(Map)");
    }

    @Override
    public boolean getHasSourceFiles() {
        throw failWithUnsupportedMethod("getHasSourceFiles()");
    }

    @Override
    public FileCollection getSourceFiles() {
        throw failWithUnsupportedMethod("getSourceFiles()");
    }
}