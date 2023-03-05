package com.tyron.builder.gradle.internal.packaging;

/**
 * User's setting for a particular archive entry. This is expressed in the build.gradle
 * DSL and used by this filter to determine file merging behaviors.
 */
public enum PackagingFileAction {
    /**
     * No action was described for archive entry.
     */
    NONE,

    /**
     * Merge all archive entries with the same archive path.
     */
    MERGE,

    /**
     * Pick to first archive entry with that archive path (not stable).
     */
    PICK_FIRST,

    /**
     * Exclude all archive entries with that archive path.
     */
    EXCLUDE
}
