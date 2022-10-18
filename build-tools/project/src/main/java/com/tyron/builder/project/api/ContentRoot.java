package com.tyron.builder.project.api;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class ContentRoot {

    private final File rootDirectory;

    private final Set<File> sourceDirectories;

    public ContentRoot(File rootDirectory) {
        this.rootDirectory = rootDirectory;

        sourceDirectories = new HashSet<>(3);
    }

    public Set<File> getSourceDirectories() {
        return sourceDirectories;
    }

    public void addSourceDirectory(File file) {
        sourceDirectories.add(file);
    }

    public File getRootDirectory() {
        return rootDirectory;
    }
}
