package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * An Android Artifact.
 *
 * This is the entry point for the output of a [Variant]. This can be more than one
 * output in the case of multi-apk where more than one APKs are generated from the same set
 * of sources.
 */
interface AndroidArtifact : AbstractArtifact, AndroidModel {

    /**
     * The min SDK version of this artifact
     */
    val minSdkVersion: ApiVersion

    /**
     * The target SDK version override. If null, there is no override and the value may
     * be coming from the manifest(s) if present there.
     * If not null, this is the final resolved value.
     */
    val targetSdkVersionOverride: ApiVersion?

    /**
     * The max SDK version of this artifact, or null if not set
     */
    val maxSdkVersion: Int?

    /**
     * Returns whether the output file is signed. This can only be true for the main apk of an
     * application project.
     *
     * @return true if the app is signed.
     */
    val isSigned: Boolean

    /**
     * Returns the name of the [SigningConfig] used for the signing. If none are setup or if
     * this is not the main artifact of an application project, then this is null.
     *
     * @return the name of the setup signing config.
     */
    val signingConfigName: String?

    /**
     * Returns the application ID of this artifact.
     *
     * Known for:
     *  - Application plugin main artifacts
     *  - AndroidTest components of all project types
     *  - Test-only plugin main artifacts
     *
     *  Not included (null) for:
     *   - Library plugin main artifacts, as no APK is produced
     *   - UnitTest components, also as no APK is produced
     *   - Dynamic feature plugin main artifacts, as the application ID comes from the base
     *     application, and is therefore not available in dynamic feature projects during
     *     configuration. In this case Android Studio must look at the dependency graph to find the
     *     base application to find this value.
     */
    val applicationId: String?

    /**
     * Returns the name of the task used to generate the source code. The actual value might
     * depend on the build system front end.
     *
     * @return the name of the code generating task.
     */
    val sourceGenTaskName: String

    /**
     * The name of the task used to generate the resources. The actual value might
     * depend on the build system front end.
     *
     * Maybe null if the artifact does not support Android resources
     */
    val resGenTaskName: String?

    /**
     * Returns all the resource folders that are generated. This is typically the renderscript
     * output and the merged resources.
     *
     * @return a list of folder.
     */
    val generatedResourceFolders: Collection<File>

    /**
     * Returns the ABI filters associated with the artifact, or null if there are no filters.
     *
     * If the list contains values, then the artifact only contains these ABIs and excludes
     * others.
     */
    val abiFilters: Set<String>?

    /**
     * Returns the absolute path for the listing file that will get updated after each build. The
     * model file will contain deployment related information like applicationId, list of APKs.
     *
     * This is null for variants that do not generate APKs (libraries).
     *
     * @return the path to a json file.
     */
    val assembleTaskOutputListingFile: File?

    /**
     * The test info, if applicable, otherwise null
     */
    val testInfo: TestInfo?

    /**
     * The bundle info if applicable, otherwise null.
     *
     * This is only applicable to the main artifact of the APPLICATION modules. All other cases
     * this should be null.
     */
    val bundleInfo: BundleInfo?

    /**
     * Returns the code shrinker used by this artifact or null if no shrinker is used to build this
     * artifact.
     */
    val codeShrinker: CodeShrinker?

    /**
     * Details about privacy sandbox SDK consumption.
     *
     * Null if the feature is not enabled, but may be present even if there are
     * no privacy sandbox SDKs that need to be deployed.
     */
    val privacySandboxSdkInfo: PrivacySandboxSdkInfo?

    /**
     * Files listing any D8 backported desugared methods or core library desugared methods for this artifact
     */
    val desugaredMethodsFiles: Collection<File>
}
