package com.tyron.builder.api.variant.impl

import com.android.SdkConstants
import com.android.ide.common.resources.AssetSet
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.util.PatternFilterable

/**
 * Specialization of [LayeredSourceDirectoriesImpl] for [SourceType.ASSETS]
 */
class AssetSourceDirectoriesImpl(
    _name: String,
    val variantServices: VariantServices,
    variantDslFilters: PatternFilterable?,
) : LayeredSourceDirectoriesImpl(_name, variantServices, variantDslFilters) {

    /**
     * Returns the dynamic list of [AssetSet] based on the current list of [DirectoryEntry]
     *
     * The list is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on. This is meant to facilitate usage of the list in an
     * asset merger
     *
     * @param aaptEnv the value of "ANDROID_AAPT_IGNORE" environment variable.
     * @return a [Provider] of a [List] of [AssetSet].
     */
    fun getAscendingOrderAssetSets(
        aaptEnv: Provider<String>
    ): Provider<List<AssetSet>> {

        return super.variantSources.map { allDirectories ->
            allDirectories.map { directoryEntries ->
                val assetName = if (directoryEntries.name == SdkConstants.FD_MAIN)
                    BuilderConstants.MAIN else directoryEntries.name

                AssetSet(assetName, aaptEnv.orNull).also {
                    it.addSources(directoryEntries.directoryEntries.map { directoryEntry ->
                        directoryEntry.asFiles(variantServices::directoryProperty).get().asFile
                    })
                }
            }
        }
    }
}
