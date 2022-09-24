package com.tyron.builder.gradle.internal.test

import com.tyron.builder.api.variant.BuiltArtifacts
import com.tyron.builder.api.variant.impl.BuiltArtifactsImpl
import com.tyron.builder.testing.api.DeviceConfigProvider
import com.android.ide.common.build.GenericArtifactType
import com.android.ide.common.build.GenericBuiltArtifact
import com.android.ide.common.build.GenericBuiltArtifacts
import com.android.ide.common.build.GenericBuiltArtifactsSplitOutputMatcher
import com.android.ide.common.build.GenericFilterConfiguration
import java.io.File

object BuiltArtifactsSplitOutputMatcher {

    /**
     * Determines and return the list of APKs to use based on given device abis.
     *
     * @param deviceConfigProvider the device configuration.
     * @param builtArtifacts the tested variant built artifacts.
     * @param variantAbiFilters a list of abi filters applied to the variant. This is used in place
     * of the outputs, if there is a single output with no abi filters. If the list is
     * empty, then the variant does not restrict ABI packaging.
     * @return the list of APK files to install.
     */
    fun computeBestOutput(
        deviceConfigProvider: DeviceConfigProvider,
        builtArtifacts: BuiltArtifactsImpl,
        variantAbiFilters: Collection<String>
    ): List<File> {
        val adaptedBuiltArtifactType = GenericBuiltArtifacts(
            version = BuiltArtifacts.METADATA_FILE_VERSION,
            artifactType = GenericArtifactType(
                builtArtifacts.artifactType.name(),
                builtArtifacts.artifactType.kind.toString()
            ),
            applicationId = builtArtifacts.applicationId,
            variantName = builtArtifacts.variantName,
            elements = builtArtifacts.elements.map { sourceBuiltArtifact ->
                GenericBuiltArtifact(
                    outputType = sourceBuiltArtifact.outputType.toString(),
                    filters = sourceBuiltArtifact.filters.map { filterConfiguration ->
                        GenericFilterConfiguration(
                            filterConfiguration.filterType.toString(),
                            filterConfiguration.identifier
                        )
                    },
                    versionCode = sourceBuiltArtifact.versionCode,
                    versionName = sourceBuiltArtifact.versionName,
                    outputFile = sourceBuiltArtifact.outputFile
                )
            },
            elementType = builtArtifacts.elementType()
        )
        // now look for a matching output file
        return GenericBuiltArtifactsSplitOutputMatcher.computeBestOutput(
            adaptedBuiltArtifactType,
            variantAbiFilters,
            deviceConfigProvider.abis
        )
    }
}