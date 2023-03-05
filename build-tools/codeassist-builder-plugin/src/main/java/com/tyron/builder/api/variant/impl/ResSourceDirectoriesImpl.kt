package com.tyron.builder.api.variant.impl

import com.android.SdkConstants
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceSet
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.util.PatternFilterable

class ResSourceDirectoriesImpl(
    _name: String,
    val variantServices: VariantServices,
    variantDslFilters: PatternFilterable?,
) : LayeredSourceDirectoriesImpl(_name, variantServices, variantDslFilters) {


    /**
     * Returns the dynamic list of [ResourceSet] for the source folders only.
     *
     *
     * The list is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on. This is meant to facilitate usage of the list in a
     * Resource merger
     *
     * @param aaptEnv the value of "ANDROID_AAPT_IGNORE" environment variable.
     * @return a list ResourceSet.
     */
    fun getAscendingOrderResourceSets(
        validateEnabled: Boolean,
        aaptEnv: String?
    ): Provider<List<ResourceSet>> {

        return super.variantSources.map { allDirectories ->
            allDirectories.map { directoryEntries ->
                val assetName = if (directoryEntries.name == SdkConstants.FD_MAIN)
                    BuilderConstants.MAIN else directoryEntries.name

                ResourceSet(
                    assetName,
                    ResourceNamespace.RES_AUTO,
                    null,
                    validateEnabled,
                    aaptEnv,
                ).also {
                    it.addSources(directoryEntries.directoryEntries.map { directoryEntry ->
                        directoryEntry.asFiles(variantServices::directoryProperty).get().asFile
                    })
                }
            }
        }
    }
}
