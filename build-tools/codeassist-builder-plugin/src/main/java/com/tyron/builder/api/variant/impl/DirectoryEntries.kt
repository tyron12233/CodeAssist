package com.tyron.builder.api.variant.impl
/**
 * Internal representation of [DirectoryEntry] that have the same priorities.
 */
class DirectoryEntries(
    val name: String,
    val directoryEntries: Collection<DirectoryEntry>
)