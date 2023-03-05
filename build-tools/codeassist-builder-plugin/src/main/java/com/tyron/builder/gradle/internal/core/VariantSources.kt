package com.tyron.builder.gradle.internal.core

import com.android.SdkConstants
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet
import com.tyron.builder.gradle.internal.utils.immutableMapBuilder
import com.tyron.builder.core.ComponentType
import com.tyron.builder.model.v2.CustomSourceDirectory
import com.tyron.builder.model.SourceProvider
import com.google.common.collect.Lists
import java.io.File
import java.util.function.Function

/**
 * Represents the sources for a Variant
 */
class VariantSources internal constructor(
    val fullName: String,
    val componentType: ComponentType,
    private val defaultSourceProvider: SourceProvider,
    private val buildTypeSourceProvider: SourceProvider? = null,
    /** The list of product flavors. Items earlier in the list override later items.  */
    private val flavorSourceProviders: List<SourceProvider>,
    /** MultiFlavors specific source provider, may be null  */
    val multiFlavorSourceProvider: DefaultAndroidSourceSet? = null,
    /** Variant specific source provider, may be null  */
    val variantSourceProvider: DefaultAndroidSourceSet? = null
) {

    /**
     * Returns the path to the main manifest file. It may or may not exist.
     *
     *
     * Note: Avoid calling this method at configuration time because the final path to the
     * manifest file may change during that time.
     */
    val mainManifestFilePath: File
        get() = defaultSourceProvider.manifestFile

    /**
     * Returns the path to the main manifest file if it exists, or `null` otherwise (e.g., the main
     * manifest file is not required to exist for a test variant or a test project).
     *
     *
     * Note: Avoid calling this method at configuration time because (1) the final path to the
     * manifest file may change during that time, and (2) this method performs I/O.
     */
    val mainManifestIfExists: File?
        get() {
            val mainManifest = mainManifestFilePath
            return if (mainManifest.isFile) {
                mainManifest
            } else null
        }

    /** Returns the path to the art profile file. It may or may not exist. */
    val artProfile: File
        get() {
            // this is really brittle, we need to review where those sources will be located and
            // what we offer to make visible in the SourceProvider interface.
            // src/main/baseline-prof.txt will do for now.
            return File(File(mainManifestFilePath.parent), SdkConstants.FN_ART_PROFILE)
        }

    /**
     * Returns a list of sorted SourceProvider in ascending order of importance. This means that
     * items toward the end of the list take precedence over those toward the start of the list.
     *
     * @return a list of source provider
     */
    fun getSortedSourceProviders(addVariantSources: Boolean = true): List<SourceProvider> {
        val providers: MutableList<SourceProvider> =
            Lists.newArrayListWithExpectedSize(flavorSourceProviders.size + 4)

        // first the default source provider
        providers.add(defaultSourceProvider)
        // the list of flavor must be reversed to use the right overlay order.
        for (n in flavorSourceProviders.indices.reversed()) {
            providers.add(flavorSourceProviders[n])
        }
        // multiflavor specific overrides flavor
        multiFlavorSourceProvider?.let(providers::add)
        // build type overrides flavors
        buildTypeSourceProvider?.let(providers::add)
        // variant specific overrides all
        if (addVariantSources) {
            variantSourceProvider?.let(providers::add)
        }

        return providers
    }

    val manifestOverlays: List<File>
        get() {
            val inputs = mutableListOf<File>()

            val gatherManifest: (SourceProvider) -> Unit = {
                val variantLocation = it.manifestFile
                if (variantLocation.isFile) {
                    inputs.add(variantLocation)
                }
            }

            variantSourceProvider?.let(gatherManifest)
            buildTypeSourceProvider?.let(gatherManifest)
            multiFlavorSourceProvider?.let(gatherManifest)
            flavorSourceProviders.forEach(gatherManifest)

            return inputs
        }

    /**
     * Returns a map af all customs source directories registered. Key is the source set name as
     * registered by the user. Value is also a map of source set name to list of folders registered
     * for this source set.
     */
    val customSourceList: Map<String, Collection<CustomSourceDirectory>>
        get() {
            return immutableMapBuilder<String, Collection<CustomSourceDirectory>> {
             getSortedSourceProviders().forEach {
                 this.put(it.name, it.customDirectories)
             }
            }.toMap()
        }
}
