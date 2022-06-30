package com.tyron.builder.api.tasks.bundling;

/**
 * Specifies the compression level of an archives contents.
 */
public enum ZipEntryCompression {
    /** Contents are not compressed */
    STORED,

    /** Contents are compressed using the 'deflate' algorithm */
    DEFLATED
}
