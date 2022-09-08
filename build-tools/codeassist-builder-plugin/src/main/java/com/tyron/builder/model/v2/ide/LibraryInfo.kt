package com.tyron.builder.model.v2.ide

/**
 * Information to identify an external library dependencies
 */
interface LibraryInfo: ComponentInfo {
    val group: String
    val name: String
    val version: String
}
