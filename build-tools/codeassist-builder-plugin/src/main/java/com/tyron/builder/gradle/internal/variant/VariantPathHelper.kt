package com.tyron.builder.gradle.internal.variant

import com.android.SdkConstants
import com.android.utils.appendCapitalized
import com.android.utils.combineAsCamelCase
import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.core.dsl.ApkProducingComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.MultiVariantComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.NestedComponentDslInfo
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.options.IntegerOption
import com.tyron.builder.gradle.options.StringOption
import com.tyron.builder.internal.utils.toStrings
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import java.io.File

class VariantPathHelper(
    val buildDirectory: DirectoryProperty,
    private val dslInfo: ComponentDslInfo,
    private val dslServices: DslServices
) {

    companion object {
        private fun computeMultiVariantComponentBaseName(
            dslInfo: ComponentDslInfo
        ): String {
            if (dslInfo !is MultiVariantComponentDslInfo) {
                return ""
            }
            val sb = StringBuilder()
            if (dslInfo.productFlavors.isNotEmpty()) {
                for ((_, name) in dslInfo.productFlavors) {
                    if (sb.isNotEmpty()) {
                        sb.append('-')
                    }
                    sb.append(name)
                }
            }

            dslInfo.buildType?.let {
                if (sb.isNotEmpty()) {
                    sb.append('-')
                }
                sb.append(it)
            }
            return sb.toString()
        }
        /**
         * Returns the full, unique name of the variant, including BuildType, flavors and test, dash
         * separated. (similar to full name but with dashes)
         *
         * @return the name of the variant
         */
        @JvmStatic
        fun computeBaseName(
            dslInfo: ComponentDslInfo
        ): String {
            val sb = StringBuilder()
            when (dslInfo) {
                is NestedComponentDslInfo -> {
                    sb.append(computeMultiVariantComponentBaseName(dslInfo.mainVariantDslInfo))
                    if (sb.isNotEmpty()) {
                        sb.append('-')
                    }
                    sb.append(dslInfo.componentType.prefix)
                }
                else -> {
                    sb.append(computeMultiVariantComponentBaseName(dslInfo))
                    if (sb.isEmpty()) {
                        sb.append("main")
                    }
                }
            }

            return sb.toString()
        }

        /**
         * Returns a full name that includes the given splits name.
         *
         * @param splitName the split name
         * @return a unique name made up of the variant and split names.
         */
        @JvmStatic
        fun computeFullNameWithSplits(
            variantConfiguration: ComponentIdentity,
            componentType: ComponentType,
            splitName: String): String {
            val sb = StringBuilder()

            val flavorName = variantConfiguration.flavorName

            if (!flavorName.isNullOrEmpty()) {
                sb.append(flavorName)
                sb.appendCapitalized(splitName)
            } else {
                sb.append(splitName)
            }

            variantConfiguration.buildType?.let {
                sb.appendCapitalized(it)
            }

            if (componentType.isNestedComponent) {
                sb.append(componentType.suffix)
            }
            return sb.toString()
        }
    }

    /**
     * Returns a unique directory name (can include multiple folders) for the variant, based on
     * build type, flavor and test.
     *
     *
     * This always uses forward slashes ('/') as separator on all platform.
     *
     * @return the directory name for the variant
     */
    val dirName: String by lazy {
        Joiner.on('/').join(directorySegments)
    }

    private fun getDirectorySegments(dslInfo: ComponentDslInfo): Collection<String> {
        val builder = ImmutableList.builder<String>()
        when (dslInfo) {
            is NestedComponentDslInfo -> {
                builder.add(dslInfo.componentType.prefix)
                builder.addAll(getDirectorySegments(dslInfo.mainVariantDslInfo))
            }
            is MultiVariantComponentDslInfo -> {
                if (dslInfo.productFlavorList.isNotEmpty()) {
                    builder.add(
                        combineAsCamelCase(
                            dslInfo.productFlavorList, com.tyron.builder.api.dsl.ProductFlavor::getName
                        )
                    )
                }
                builder.add(dslInfo.buildType!!)
            }
            else -> {
                builder.add("main")
            }
        }
        return builder.build()
    }

    /**
     * Returns a unique directory name (can include multiple folders) for the variant, based on
     * build type, flavor and test.
     *
     * @return the directory name for the variant
     */
    val directorySegments: Collection<String?> by lazy {
        getDirectorySegments(dslInfo)
    }

    /**
     * Returns the expected output file name for the variant.
     *
     * @param archivesBaseName the project's archiveBaseName
     * @param baseName the variant baseName
     */
    fun getOutputFileName(archivesBaseName: String, baseName: String): String {
        // we only know if it is signed during configuration, if it's the base module.
        // Otherwise, don't differentiate between signed and unsigned.
        val suffix =
            if ((dslInfo as? ApkProducingComponentDslInfo)?.isSigningReady == true
                || !dslInfo.componentType.isBaseModule)
                SdkConstants.DOT_ANDROID_PACKAGE
            else "-unsigned.apk"
        return "$archivesBaseName-$baseName$suffix"
    }

    /**
     * Returns a full name that includes the given splits name.
     *
     * @param splitName the split name
     * @return a unique name made up of the variant and split names.
     */
    fun computeFullNameWithSplits(splitName: String): String {
        return computeFullNameWithSplits(
            dslInfo.componentIdentity,
            dslInfo.componentType,
            splitName
        )
    }

    /**
     * Returns the full, unique name of the variant, including BuildType, flavors and test, dash
     * separated. (similar to full name but with dashes)
     *
     * @return the name of the variant
     */
    val baseName: String by lazy {
        computeBaseName(
            dslInfo
        )
    }

    private fun computeBaseNameWithSplits(splitName: String, dslInfo: ComponentDslInfo): String {
        val sb = StringBuilder()
        when (dslInfo) {
            is NestedComponentDslInfo -> {
                sb.append(computeBaseNameWithSplits(splitName, dslInfo.mainVariantDslInfo))
                sb.append('-').append(dslInfo.componentType.prefix)
            }
            is MultiVariantComponentDslInfo -> {
                if (dslInfo.productFlavorList.isNotEmpty()) {
                    for (pf in dslInfo.productFlavorList) {
                        sb.append(pf.name).append('-')
                    }
                }
                sb.append(splitName).append('-')
                sb.append(dslInfo.buildType!!)
            }
            else -> {
                return "main-$splitName"
            }
        }
        return sb.toString()
    }

    /**
     * Returns a base name that includes the given splits name.
     *
     * @param splitName the split name
     * @return a unique name made up of the variant and split names.
     */
    fun computeBaseNameWithSplits(splitName: String): String {
        return computeBaseNameWithSplits(splitName, dslInfo)
    }

    fun intermediatesDir(vararg subDirs: String): Provider<Directory> =
        getBuildSubDir(SdkConstants.FD_INTERMEDIATES, subDirs)

    fun outputDir(vararg subDirs: String): Provider<Directory> =
        getBuildSubDir(SdkConstants.FD_OUTPUTS, subDirs)

    fun generatedDir(vararg subDirs: String): Provider<Directory> =
        getBuildSubDir(SdkConstants.FD_GENERATED, subDirs)

    fun reportsDir(vararg subDirs: String): Provider<Directory> =
        getBuildSubDir(BuilderConstants.FD_REPORTS, subDirs)

    val buildConfigSourceOutputDir: Provider<Directory>
            by lazy { generatedDir("source", "buildConfig", dirName) }

    val renderscriptObjOutputDir: Provider<Directory>
            by lazy {
                getBuildSubDir(
                    SdkConstants.FD_INTERMEDIATES,
                    toStrings("rs", directorySegments, "obj").toTypedArray()
                )
            }

    val coverageReportDir: Provider<Directory>
            by lazy { reportsDir("coverage", dirName) }

    /**
     * Obtains the location where APKs should be placed.
     *
     * @return the location for APKs
     */
    val apkLocation: File
            by lazy {
                val override = dslServices.projectOptions.get(StringOption.IDE_APK_LOCATION)
                // it does not really matter if the build was invoked from the IDE or not, it only
                // matters if it is an 'optimized' build and in that case, we consider it a
                // custom build.
                val customBuild =
                    dslServices.projectOptions.get(StringOption.IDE_BUILD_TARGET_DENSITY) != null ||
                            dslServices.projectOptions.get(StringOption.IDE_BUILD_TARGET_ABI) != null ||
                            dslServices.projectOptions.get(IntegerOption.IDE_TARGET_DEVICE_API) != null
                val baseDirectory =when {
                    override != null -> dslServices.file(override)
                    customBuild ->  deploymentApkLocation.get().asFile
                    else -> defaultApkLocation.get().asFile
                }
                File(baseDirectory, dirName)
            }

    /**
     * Obtains the default location for APKs.
     */
    private val defaultApkLocation: Provider<Directory>
            by lazy { outputDir("apk") }

    /**
     * Obtains the location for APKs that target a specific device.
     *
     * APKs built for a specific device are put in intermediates/ in order to
     * distinguish them from other APKs
     *
     * @return the location for targeted APKs
     */
    private val deploymentApkLocation: Provider<Directory> by lazy {
        intermediatesDir("apk")
    }

    val aarLocation: Provider<Directory>
            by lazy { outputDir(BuilderConstants.EXT_LIB_ARCHIVE) }

    /**
     * Returns a place to store incremental build data. The {@code name} argument has to be unique
     * per task, ideally generated with [TaskInformation.name].
     */
    fun getIncrementalDir(name: String): File {
        return intermediatesDir("incremental", name).get().asFile
    }

    fun getGeneratedResourcesDir(name: String): Provider<Directory> {
        val dirs: List<String> =
            listOf("res", name) + directorySegments.filterNotNull()
        return generatedDir(*dirs.toTypedArray())
    }

    private fun getBuildSubDir(childDir: String, subDirs: Array<out String>): Provider<Directory> {
        // Prevent accidental usage with files.
        if (subDirs.any() && subDirs.last().contains('.')) {
            throw IllegalStateException("Directory should not contain '.'.")
        }
        return buildDirectory.dir("$childDir${subDirs.joinToString(separator = "/", prefix = "/")}")
    }
}