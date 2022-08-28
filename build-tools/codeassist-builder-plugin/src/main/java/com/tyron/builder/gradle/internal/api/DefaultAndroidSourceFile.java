package com.tyron.builder.gradle.internal.api;

import com.tyron.builder.gradle.api.AndroidSourceFile;

import java.io.File;
import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;

/**
 */
public class DefaultAndroidSourceFile implements AndroidSourceFile {

    private final String name;
    private final Project project;
    private Object source;

    DefaultAndroidSourceFile(String name, Project project) {
        this.name = name;
        this.project = project;
    }

    @NotNull
    @Override
    public String getName() {
        return name;
    }

    @Override
    public AndroidSourceFile srcFile(Object o) {
        source = o;
        return this;
    }

    @NotNull
    @Override
    public File getSrcFile() {
        return project.file(source);
    }

    @Override
    public String toString() {
        return source.toString();
    }
}