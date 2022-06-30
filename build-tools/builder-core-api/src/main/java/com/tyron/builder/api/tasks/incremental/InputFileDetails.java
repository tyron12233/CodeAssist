package com.tyron.builder.api.tasks.incremental;

import java.io.File;

/**
 * A change to an input file.
 */
public interface InputFileDetails {
    /**
     * Was the file added?
     * @return true if the file was added since the last execution
     */
    boolean isAdded();

    /**
     * Was the file modified?
     * @return if the file was modified
     */
    boolean isModified();

    /**
     * Was the file removed?
     * @return true if the file was removed since the last execution
     */
    boolean isRemoved();

    /**
     * The input file, which may no longer exist.
     * @return the input file
     */
    File getFile();
}