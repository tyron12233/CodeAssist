package com.tyron.builder.api.file;


import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;

/**
 * <p>A directory with some associated include and exclude patterns.</p>
 *
 * <p>This interface does not allow mutation. However, the actual implementation may not be immutable.</p>
 */
public interface DirectoryTree {
    /**
     * Returns the base directory of this tree.
     * @return The base dir, never returns null.
     */
    File getDir();

    /**
     * Returns the patterns which select the files under the base directory.
     *
     * @return The patterns, never returns null.
     */
    PatternSet getPatterns();
}