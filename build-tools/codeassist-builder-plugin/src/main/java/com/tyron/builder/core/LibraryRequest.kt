package com.tyron.builder.core

/**
 * a request for an optional library.
 */
data class LibraryRequest(
    /**
     * The name of the library. This is the unique name that will show up in the manifest.
     */
    val name: String,
    /**
     * Whether the library is required by the app or just optional.
     */
    val isRequired: Boolean
)