package com.tyron.builder.internal.watch.vfs.impl;

import com.tyron.builder.internal.file.FileHierarchySet;
import com.tyron.builder.internal.vfs.FileSystemAccess;

import java.util.concurrent.atomic.AtomicReference;

public class LocationsWrittenByCurrentBuild implements FileSystemAccess.WriteListener {
    private final AtomicReference<FileHierarchySet> producedByCurrentBuild = new AtomicReference<>(FileHierarchySet.empty());
    private volatile boolean buildRunning;

    @Override
    public void locationsWritten(Iterable<String> locations) {
        if (buildRunning) {
            producedByCurrentBuild.updateAndGet(currentValue -> {
                FileHierarchySet newValue = currentValue;
                for (String location : locations) {
                    newValue = newValue.plus(location);
                }
                return newValue;
            });
        }
    }

    public boolean wasLocationWritten(String location) {
        return producedByCurrentBuild.get().contains(location);
    }

    public void buildStarted() {
        producedByCurrentBuild.set(FileHierarchySet.empty());
        buildRunning = true;
    }

    public void buildFinished() {
        buildRunning = false;
        producedByCurrentBuild.set(FileHierarchySet.empty());
    }
}