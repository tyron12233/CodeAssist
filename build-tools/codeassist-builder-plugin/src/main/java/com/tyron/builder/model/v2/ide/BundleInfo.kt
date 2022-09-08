package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * Information for artifact that generates bundle
 *
 * @since 4.2
 */
interface BundleInfo: AndroidModel {

    /**
     * The name of the task used to generate the bundle file (.aab)
     */
    val bundleTaskName: String

    /**
     * The path to the listing file generated after each [bundleTaskName] task
     * execution. The listing file will contain a reference to the produced bundle file (.aab).
     */
    val bundleTaskOutputListingFile: File

    /**
     * The name of the task used to generate APKs via the bundle file (.aab)
     */
    val apkFromBundleTaskName: String

    /**
     * The path to the model file generated after each [apkFromBundleTaskName]
     * task execution. The model will contain a reference to the folder where APKs from bundle are
     * placed into.
     */
    val apkFromBundleTaskOutputListingFile: File
}
