package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Java compile options.
 *
 * @since 4.2
 */
interface JavaCompileOptions: AndroidModel {
    /**
     * @return the java compiler encoding setting.
     */
    val encoding: String

    /**
     * @return the level of compliance Java source code has.
     */
    val sourceCompatibility: String

    /**
     * @return the Java version to be able to run classes on.
     */
    val targetCompatibility: String

    /** @return true if core library desugaring is enabled, false otherwise.
     */
    val isCoreLibraryDesugaringEnabled: Boolean
}
