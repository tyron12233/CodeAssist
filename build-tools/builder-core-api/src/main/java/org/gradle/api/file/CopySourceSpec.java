package org.gradle.api.file;

import groovy.lang.Closure;
import org.gradle.api.Action;

/**
 * Specifies sources for a file copy.
 */
public interface CopySourceSpec {
    /**
     * Specifies source files or directories for a copy. The given paths are evaluated as per {@link
     * org.gradle.api.Project#files(Object...)}.
     *f
     * @param sourcePaths Paths to source files for the copy
     */
    CopySourceSpec from(Object... sourcePaths);

    /**
     * Specifies the source files or directories for a copy and creates a child {@code CopySourceSpec}. The given source
     * path is evaluated as per {@link org.gradle.api.Project#files(Object...)} .
     *
     * @param sourcePath Path to source for the copy
     * @param configureClosure closure for configuring the child CopySourceSpec
     */
    CopySourceSpec from(Object sourcePath, Closure configureClosure);

    /**
     * Specifies the source files or directories for a copy and creates a child {@code CopySpec}. The given source
     * path is evaluated as per {@link org.gradle.api.Project#files(Object...)} .
     *
     * @param sourcePath Path to source for the copy
     * @param configureAction action for configuring the child CopySpec
     */
    CopySourceSpec from(Object sourcePath, Action<? super CopySpec> configureAction);
}
