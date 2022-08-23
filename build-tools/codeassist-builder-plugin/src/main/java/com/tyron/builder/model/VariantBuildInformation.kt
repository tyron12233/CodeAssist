package com.tyron.builder.model
/**
 * Minimum information about variants that always get transferred with the [AndroidProject]
 * irrespective of per variant sync.
 *
 * Adding new elements to this interface should be carefully considered as we want to keep this
 * instances as small as possible.
 *
 * @since 4.1
 */
interface VariantBuildInformation {
    val variantName: String
    /**
     * Returns the name of the task used to generate the artifact output(s).
     *
     * @return the name of the task.
     */
    val assembleTaskName: String

    /**
     * Returns the absolute path for the listing file that will get updated after each build. The
     * model file will contain deployment related information like applicationId, list of APKs.
     *
     * @since 4.0
     * @return the path to a json file.
     */
    val assembleTaskOutputListingFile: String?

    /**
     * Returns the name of the task used to generate the bundle file (.aab), or null if the task is
     * not supported.
     *
     * @since 4.0
     * @return name of the task used to generate the bundle file (.aab)
     */
    val bundleTaskName: String?

    /**
     * Returns the path to the listing file generated after each [.getBundleTaskName] task
     * execution. The listing file will contain a reference to the produced bundle file (.aab).
     * Returns null when [.getBundleTaskName] returns null.
     *
     * @since 4.0
     * @return the file path for the bundle model file.
     */
    val bundleTaskOutputListingFile: String?

    /**
     * Returns the name of the task used to generate APKs via the bundle file (.aab), or null if the
     * task is not supported.
     *
     * @since 4.0
     * @return name of the task used to generate the APKs via the bundle
     */
    val apkFromBundleTaskName: String?

    /**
     * Returns the path to the model file generated after each [.getApkFromBundleTaskName]
     * task execution. The model will contain a reference to the folder where APKs from bundle are
     * placed into. Returns null when [.getApkFromBundleTaskName] returns null.
     *
     * @since 4.0
     * @return the file path for the [.getApkFromBundleTaskName] output model.
     */
    val apkFromBundleTaskOutputListingFile: String?
}