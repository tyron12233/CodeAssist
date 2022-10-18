package com.tyron.builder.compiler.manifest;

/**
 * Defines the default merging activity for same type.
 *
 * WIP more work needed.
 */
public enum MergeType {

    /**
     * Merge this element's children with lower priority element's children. Do not merge
     * element's attributes.
     */
    MERGE_CHILDREN_ONLY,

    /**
     * Merge this element with lower priority elements.
     */
    MERGE,

    /**
     * Always generate a merging failure when encountering lower priority elements.
     */
    CONFLICT,

    /**
     * Do not attempt to merge with lower priority elements.
     */
    IGNORE,

    /**
     * Always consume lower priority elements unless it is strictly equals to the higher priority
     * element.
     */
    ALWAYS,
}
