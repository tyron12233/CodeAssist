package com.tyron.builder.common.resources;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;

/**
 * Provides functionality the resource merger needs for preprocessing resources during merge.
 * Implementations of this interface must be thread-safe.
 */
public interface ResourcePreprocessor extends Serializable {
    /**
     * Returns the paths that should be generated for the given file, which can be empty if the file
     * doesn't need to be preprocessed.
     */
    @NotNull
    Collection<File> getFilesToBeGenerated(@NotNull File original) throws IOException;

    /** Actually generate the file based on the original file. */
    void generateFile(@NotNull File toBeGenerated, @NotNull File original) throws IOException;
}