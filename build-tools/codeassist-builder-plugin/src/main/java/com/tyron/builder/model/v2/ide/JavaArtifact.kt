package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * The information for a generated Java artifact.
 *
 * This artifact is for Java components inside an Android project, which is only unit tests
 * for now.
 *
 * @since 4.2
 */
interface JavaArtifact : AbstractArtifact, AndroidModel {
    /** Path to the mockable platform jar generated for this [JavaArtifact], if present.  */
    val mockablePlatformJar: File?

    /**
     * Returns the folder containing resource files that classes from this artifact expect to find
     * on the classpath.
     *
     * This is used to run the unit tests
     */
    val runtimeResourceFolder: File?
}
