package com.tyron.builder.common.resources;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

/**
 * A Source sets that contains a list of source files/folders
 */
public interface SourceSet {
    /**
     * Returns a list of Source files or folders.
     * @return a non null list.
     */
    @NotNull
    List<File> getSourceFiles();

    /**
     * Finds and returns a Source file/folder containing a given file.
     *
     * It doesn't actually check if the file exists, instead just cares about the file path.
     *
     * @param file the file to search for
     * @return the source file containing the file or null if none are found.
     */
    File findMatchingSourceFile(File file);
}