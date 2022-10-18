@file:JvmName("AaptV2CommandBuilder")

package com.tyron.builder.internal.aapt.v2

import com.android.SdkConstants
import com.android.utils.FileUtils
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import com.android.ide.common.resources.CompileResourceRequest
import com.tyron.builder.internal.aapt.AaptConvertConfig
import com.tyron.builder.internal.aapt.AaptException
import com.tyron.builder.internal.aapt.AaptPackageConfig
import com.tyron.builder.internal.aapt.AaptUtils
import com.tyron.builder.packaging.PackagingUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.util.ArrayList
import java.util.Locale
import java.util.Objects

/**
 * Creates the command line used to compile a resource. See [Aapt2DaemonImpl]
 *
 * @return the command line arguments
 */
fun makeCompileCommand(request: CompileResourceRequest): ImmutableList<String> {
    val parameters = ImmutableList.Builder<String>()

    if (request.isPseudoLocalize) {
        parameters.add("--pseudo-localize")
    }

    if (!request.isPngCrunching) {
        // Only pass --no-crunch for png files and not for 9-patch files as that breaks them.
        val lowerName = request.inputFile.path.lowercase(Locale.US)
        if (lowerName.endsWith(SdkConstants.DOT_PNG)
                && !lowerName.endsWith(SdkConstants.DOT_9PNG)) {
            parameters.add("--no-crunch")
        }
    }

    if (request.partialRFile != null) {
        parameters.add("--output-text-symbols", request.partialRFile!!.absolutePath)
    }

    parameters.add("--legacy")
    parameters.add("-o", request.outputDirectory.absolutePath)
    parameters.add(request.inputFile.absolutePath)

    request.sourcePath.let {
        parameters.add("--source-path")
        parameters.add(it)
    }
    return parameters.build()
}

/**
 * Creates the command line used to link the package.
 *
 *
 * See [Aapt2.link].
 *
 * @param config see above
 * @return the command line arguments
 * @throws AaptException failed to build the command line
 */
@Throws(AaptException::class)
fun makeLinkCommand(config: AaptPackageConfig): ImmutableList<String> {
    val builder = ImmutableList.builder<String>()

    if (config.verbose) {
        builder.add("-v")
    }

    if (config.generateProtos) {
        builder.add("--proto-format")
    }

    if (config.excludeSources) {
        if (!config.generateProtos) {
            throw AaptException("AAPT2 only supports excluding sources when building for a bundle.")
        }
        builder.add("--exclude-sources")
    }

    // inputs
    if (config.mergeOnly) {
        builder.add("--merge-only")
    } else {
        builder.add("-I", config.androidJarPath!!)
    }

    config.imports.forEach { file -> builder.add("-I", file.absolutePath) }

    val manifestFile = config.manifestFile
    Preconditions.checkNotNull(manifestFile)
    builder.add("--manifest", manifestFile.absolutePath)

    val resourceOutputApk = config.resourceOutputApk
    FileUtils.mkdirs(resourceOutputApk.parentFile)
    builder.add("-o", resourceOutputApk.absolutePath)

    if (!config.resourceDirs.isEmpty()) {
        try {
            if (config.isListResourceFiles()) {
                // AAPT2 only accepts individual files passed to the -R flag. In order to not
                // pass every single resource file, instead create a temporary file containing a
                // list of resource files and pass it as the only -R argument.
                val resourceListFile = File(
                        config.intermediateDir!!,
                        "resources-list-for-" + resourceOutputApk.name + ".txt"
                )

                // Resources list could have changed since last run.
                FileUtils.deleteIfExists(resourceListFile)
                Files.createDirectories(config.intermediateDir.toPath())
                for (dir in config.resourceDirs) {
                    FileOutputStream(resourceListFile).use { fos ->
                        PrintWriter(fos).use { pw ->
                            dir.listFiles()!!
                                .filter { it.isFile }
                                .sortedBy { it.path }
                                .forEach { pw.print(it.absolutePath + " ") }
                        }
                    }
                }
                builder.add("-R", "@" + resourceListFile.absolutePath)
            } else {
                for (dir in config.resourceDirs) {
                    dir.listFiles()!!
                        .filter { it.isFile }
                        .sortedBy { it.path }
                        .forEach { builder.add("-R", it.absolutePath) }
                }
            }
        } catch (e: IOException) {
            throw AaptException(
                    "Failed to walk paths " +
                            Joiner.on(File.pathSeparatorChar).join(config.resourceDirs),
                    e
            )
        }

    }

    builder.add("--auto-add-overlay")

    // outputs
    if (config.sourceOutputDir != null) {
        builder.add("--java", config.sourceOutputDir.absolutePath)
    }

    if (config.proguardOutputFile != null) {
        builder.add("--proguard", config.proguardOutputFile.absolutePath)
    }

    if (config.mainDexListProguardOutputFile != null) {
        builder.add(
                "--proguard-main-dex",
                config.mainDexListProguardOutputFile.absolutePath
        )
    }

    if (config.splits != null) {
        for (split in config.splits) {
            val splitter = File.pathSeparator
            builder.add(
                    "--split",
                    resourceOutputApk.toString() + "_" + split + splitter + split
            )
        }
    }

    // options controlled by build variants
    if (!config.componentType.isNestedComponent && config.customPackageForR != null) {
        builder.add("--custom-package", config.customPackageForR)
    }

    // bundle specific options
    var generateFinalIds = true
    if (config.componentType.isAar) {
        generateFinalIds = false
    }
    if (!generateFinalIds) {
        builder.add("--non-final-ids")
    }

    /*
     * Never compress apks.
     */
    builder.add("-0", "apk")

    /*
     * Add custom no-compress extensions.
     */
    val noCompressList = Objects.requireNonNull(config.options).noCompress
    if (noCompressList != null && noCompressList.isNotEmpty()) {
        if (noCompressList.any { Strings.isNullOrEmpty(it)}) {
            // Do not compress anything.
            builder.add("--no-compress")
        } else {
            // Join the extensions into a regex and pass to "--no-compress-regex" flag which is
            // non-case sensitive. We need to use "$" to mark these as extensions, and the "|" for
            // alternations.
            // AAPT2 will still not compress the default no-compress extensions, for example ".jpg"
            // or ".mp3". For full list see PackagingUtils.DEFAULT_DONT_COMPRESS_EXTENSIONS.
            builder.add("--no-compress-regex", getNoCompressRegex(noCompressList))
        }
    }


    val additionalParameters = config.options.additionalParameters
    if (additionalParameters != null) {
        builder.addAll(additionalParameters)
    }

    val resourceConfigs = ArrayList(config.resourceConfigs)

    /*
     * Split the density and language resource configs, since starting in 21, the
     * density resource configs should be passed with --preferred-density to ensure packaging
     * of scalable resources when no resource for the preferred density is present.
     */
    val densityResourceConfigs = Lists.newArrayList(
            AaptUtils.getDensityResConfigs(resourceConfigs)
    )
    val otherResourceConfigs = Lists.newArrayList(
            AaptUtils.getNonDensityResConfigs(
                    resourceConfigs
            )
    )
    var preferredDensity = config.preferredDensity

    val densityResSplits = if (config.splits != null)
        AaptUtils.getDensityResConfigs(config.splits)
    else
        ImmutableList.of()

    if ((preferredDensity != null || densityResSplits.iterator().hasNext())
            && densityResourceConfigs.isNotEmpty()) {
        throw AaptException(
                String.format(
                        "When using splits in tools 21 and above, "
                                + "resConfigs should not contain any densities. Right now, it "
                                + "contains \"%1\$s\"\nSuggestion: remove these from resConfigs"
                                + " from build.gradle",
                        Joiner.on("\",\"").join(densityResourceConfigs)
                )
        )
    }

    if (densityResourceConfigs.size > 1) {
        throw AaptException(
                "Cannot filter assets for multiple densities using "
                        + "SDK build tools 21 or later. Consider using apk splits instead."
        )
    }

    // if we are in split mode and resConfigs has been specified, we need to add all the
    // non density based splits back to the resConfigs otherwise they will be filtered out.
    if (otherResourceConfigs.isNotEmpty() && config.splits != null) {
        val nonDensitySplits = AaptUtils.getNonDensityResConfigs(config.splits)
        otherResourceConfigs.addAll(Lists.newArrayList(nonDensitySplits))
    }

    if (preferredDensity == null && densityResourceConfigs.size == 1) {
        preferredDensity = Iterables.getOnlyElement(densityResourceConfigs)
    }

    if (otherResourceConfigs.isNotEmpty()) {
        val joiner = Joiner.on(',')
        builder.add("-c", joiner.join(otherResourceConfigs))
    }

    if (preferredDensity != null) {
        builder.add("--preferred-density", preferredDensity)
    }

    if (config.symbolOutputDir != null) {
        val rDotTxt = File(config.symbolOutputDir, "R.txt")
        builder.add("--output-text-symbols", rDotTxt.absolutePath)
    }

    if (config.packageId != null) {
        if (config.allowReservedPackageId) {
            builder.add("--allow-reserved-package-id")
        }
        builder.add("--package-id", "0x" + Integer.toHexString(config.packageId))
        for (dependentFeature in config.dependentFeatures) {
            builder.add("-I", dependentFeature.absolutePath)
        }
    } else if (!config.dependentFeatures.isEmpty()) {
        throw AaptException("Dependent features configured but no package ID was set.")
    }

    builder.add("--no-version-vectors")

    if (config.useConditionalKeepRules) {
        builder.add("--proguard-conditional-keep-rules")
    }

    if (config.useMinimalKeepRules) {
        builder.add("--proguard-minimal-keep-rules")
    }

    if (config.isStaticLibrary()) {
        builder.add("--static-lib")
        if (!config.staticLibraryDependencies.isEmpty()) {
            throw AaptException(
                    "Static libraries to link against should be passed as imports"
            )
        }
    } else {
        for (file in config.staticLibraryDependencies) {
            builder.add(file.absolutePath)
        }
    }

    builder.add("--no-proguard-location-reference")

    if (config.emitStableIdsFile != null) {
        builder.add("--emit-ids", config.emitStableIdsFile.absolutePath)
    }

    if (config.consumeStableIdsFile != null) {
        builder.add("--stable-ids", config.consumeStableIdsFile.absolutePath)
    }

    return builder.build()
}

/**
 * Creates the command line used to convert the resources between proto/binary formats.
 *
 * See [Aapt2.convert].
 *
 * @return the command line arguments
 */
fun makeConvertCommand(config: AaptConvertConfig): ImmutableList<String> {
    val builder = ImmutableList.builder<String>()

    builder.add("--output-format")
    if (config.convertToProtos) {
        builder.add("proto")
    } else {
        builder.add("binary")
    }

    FileUtils.mkdirs(config.outputFile.parentFile)
    builder.add("-o").add(config.outputFile.absolutePath)

    builder.add(config.inputFile.absolutePath)

    return builder.build()
}

private fun getNoCompressRegex(noCompressList: Collection<String>) : String {
    return "((${Joiner.on("$)|(").join(PackagingUtils.getNoCompressForAapt(noCompressList))}$))"
}