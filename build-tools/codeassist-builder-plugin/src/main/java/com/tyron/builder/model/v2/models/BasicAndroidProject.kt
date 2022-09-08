package com.tyron.builder.model.v2.models

import com.tyron.builder.model.v2.AndroidModel
import com.tyron.builder.model.v2.ide.BasicVariant
import com.tyron.builder.model.v2.ide.ProjectType
import com.tyron.builder.model.v2.ide.SourceSetContainer
import java.io.File

/**
 * Basic Entry point for the model of the Android Projects. This models a single module, whether the
 * module is an app project, a library project, an Instant App feature project, an instantApp bundle
 * project, or a dynamic feature split project.
 *
 * This part only contains the most basic information: the source folders. For more information
 * see [AndroidProject]
 */
interface BasicAndroidProject: AndroidModel {

    /**
     * The path of the module.
     */
    val path: String

    /**
     * Then name of the build this project belongs to.
     */
    val buildName: String

    /**
     * The type of project: Android application, library, feature, instantApp.
     */
    val projectType: ProjectType

    val mainSourceSet: SourceSetContainer?

    val buildTypeSourceSets: Collection<SourceSetContainer>

    val productFlavorSourceSets: Collection<SourceSetContainer>

    /**
     * The list of all the variants.
     *
     * This does not include test variant. Test variants are additional artifacts in their
     * respective variant info.
     *
     * This only contains the most basic information about the variant: their buildtype/flavors
     * and their source sets.
     */
    val variants: Collection<BasicVariant>

    /**
     * The boot classpath matching the compile target. This is typically android.jar plus
     * other optional libraries.
     */
    val bootClasspath: Collection<File>

    /**
     * Returns the build folder of this project.
     */
    val buildFolder: File
}
