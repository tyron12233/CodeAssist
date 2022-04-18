package com.tyron.builder.api.file;

/**
 * The type of a file.
 *
 * @since 5.4
 */
public enum FileType {
    FILE,
    DIRECTORY,
    /**
     * Element of an input property pointing to a non-existing file.
     */
    MISSING
}