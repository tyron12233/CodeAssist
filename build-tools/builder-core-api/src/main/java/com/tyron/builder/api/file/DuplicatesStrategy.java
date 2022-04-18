package com.tyron.builder.api.file;


/**
 * Strategies for dealing with the potential creation of duplicate files for or archive entries.
 */
public enum DuplicatesStrategy {

    /**
     * Do not attempt to prevent duplicates.
     * <p>
     * If the destination of the operation supports duplicates (e.g. zip files) then a duplicate entry will be created.
     * If the destination does not support duplicates, the existing destination entry will be overridden with the duplicate.
     */
    INCLUDE,

    /**
     * Do not allow duplicates by ignoring subsequent items to be created at the same path.
     * <p>
     * If an attempt is made to create a duplicate file/entry during an operation, ignore the item.
     * This will leave the file/entry that was first copied/created in place.
     */
    EXCLUDE,

    /**
     * Do not attempt to prevent duplicates, but log a warning message when multiple items
     * are to be created at the same path.
     * <p>
     * This behaves exactly as INCLUDE otherwise.
     */
    WARN,

    /**
     * Throw a {@link DuplicateFileCopyingException} when subsequent items are to be created at the same path.
     * <p>
     * Use this strategy when duplicates are an error condition that should cause the build to fail.
     */
    FAIL,

    /**
     * The default strategy, which is to inherit the strategy from the parent copy spec, if any,
     * or {@link DuplicatesStrategy#INCLUDE} if the copy spec has no parent.
     *
     * @since 5.0
     */
    INHERIT
}