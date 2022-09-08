package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Options for build-time support for vector drawables.
 *
 * @since 4.2
 */
interface VectorDrawablesOptions: AndroidModel {
    val generatedDensities: Set<String>?
    val useSupportLibrary: Boolean?
}