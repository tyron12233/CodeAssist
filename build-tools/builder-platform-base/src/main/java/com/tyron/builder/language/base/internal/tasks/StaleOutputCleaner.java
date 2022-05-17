package com.tyron.builder.language.base.internal.tasks;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.execution.history.OutputsCleaner;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.FileType;

import javax.annotation.CheckReturnValue;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class StaleOutputCleaner {

    /**
     * Clean up the given stale output files under the given directory.
     *
     * Any files and directories are removed that are descendants of {@code directoryToClean}.
     * Files and directories outside {@code directoryToClean} and {@code directoryToClean} itself is not deleted.
     *
     * Returns {code true} if any file or directory was deleted, {@code false} otherwise.
     */
    @CheckReturnValue
    public static boolean cleanOutputs(Deleter deleter, Iterable<File> filesToDelete, File directoryToClean) {
        return cleanOutputs(deleter, filesToDelete, ImmutableSet.of(directoryToClean));
    }

    /**
     * Clean up the given stale output files under the given directories.
     *
     * Any files and directories are removed that are descendants of any of the {@code directoriesToClean}.
     * Files and directories outside {@code directoriesToClean} and {@code directoriesToClean} themselves are not deleted.
     *
     * Returns {code true} if any file or directory was deleted, {@code false} otherwise.
     */
    @CheckReturnValue
    public static boolean cleanOutputs(Deleter deleter, Iterable<File> filesToDelete, ImmutableSet<File> directoriesToClean) {
        Set<String> prefixes = directoriesToClean.stream()
            .map(directoryToClean -> directoryToClean.getAbsolutePath() + File.separator)
            .collect(Collectors.toSet());

        OutputsCleaner outputsCleaner = new OutputsCleaner(
            deleter,
            file -> {
                String absolutePath = file.getAbsolutePath();
                return prefixes.stream()
                    .anyMatch(absolutePath::startsWith);
            },
            dir -> !directoriesToClean.contains(dir)
        );

        try {
            for (File f : filesToDelete) {
                if (f.isFile()) {
                    outputsCleaner.cleanupOutput(f, FileType.RegularFile);
                }
            }
            outputsCleaner.cleanupDirectories();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean up stale outputs", e);
        }

        return outputsCleaner.getDidWork();
    }
}
