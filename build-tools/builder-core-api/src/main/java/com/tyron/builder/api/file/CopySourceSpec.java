package com.tyron.builder.api.file;

import com.tyron.builder.api.Action;

/**
 * Specifies sources for a file copy.
 */
public interface CopySourceSpec {
    /**
     * Specifies source files or directories for a copy. The given paths are evaluated as per {@link
     * org.gradle.api.Project#files(Object...)}.
     *
     * @param sourcePaths Paths to source files for the copy
     */
    CopySourceSpec from(Object... sourcePaths);

    /**
     * Specifies the source files or directories for a copy and creates a child {@code CopySpec}. The given source
     * path is evaluated as per {@link org.gradle.api.Project#files(Object...)} .
     *
     * @param sourcePath Path to source for the copy
     * @param configureAction action for configuring the child CopySpec
     */
    CopySourceSpec from(Object sourcePath, Action<? super CopySpec> configureAction);
}