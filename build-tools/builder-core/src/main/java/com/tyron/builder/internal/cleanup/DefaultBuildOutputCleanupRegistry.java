package com.tyron.builder.internal.cleanup;

import com.google.common.collect.Sets;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.BuildOutputCleanupRegistry;
import com.tyron.builder.api.internal.file.FileCollectionFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

public class DefaultBuildOutputCleanupRegistry implements BuildOutputCleanupRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBuildOutputCleanupRegistry.class);

    private final Object lock = new Object();

    private final FileCollectionFactory fileCollectionFactory;
    private final Set<FileCollection> outputs = Sets.newHashSet();
    private Set<String> resolvedPaths;

    public DefaultBuildOutputCleanupRegistry(FileCollectionFactory fileCollectionFactory) {
        this.fileCollectionFactory = fileCollectionFactory;
    }

    @Override
    public void registerOutputs(Object files) {
        synchronized (lock) {
            if (resolvedPaths != null) {
                // Some tasks cannot declare the dependencies on other projects, yet, for example the dependencies task.
                // When configure on demand is enabled, those other projects are realized at execution time, long after the BuildOutputRegistry
                // has been finalized. We ignore those problems for now, until the dependencies can be declared properly.
                // See https://github.com/gradle/gradle/issues/18460.
                LOGGER.debug("More outputs are being registered even though the build output cleanup registry has already been finalized. New outputs: " + files);
            } else {
                this.outputs.add(fileCollectionFactory.resolving(files));
            }
        }
    }

    @Override
    public boolean isOutputOwnedByBuild(File file) {
        Set<String> safeToDelete = getResolvedPaths();
        File absoluteFile = file.getAbsoluteFile();
        while (absoluteFile != null) {
            if (safeToDelete.contains(absoluteFile.getPath())) {
                return true;
            }
            absoluteFile = absoluteFile.getParentFile();
        }
        return false;
    }

    @Override
    public void resolveOutputs() {
        if (resolvedPaths == null) {
            Set<String> result = new LinkedHashSet<>();
            for (FileCollection output : outputs) {
                for (File file : output) {
                    result.add(file.getAbsolutePath());
                }
            }
            resolvedPaths = result;
        }
    }

    @Override
    public Set<FileCollection> getRegisteredOutputs() {
        return outputs;
    }

    private Set<String> getResolvedPaths() {
        if (resolvedPaths == null) {
            throw new BuildException("Build outputs have not been resolved yet");
        }
        return resolvedPaths;
    }
}