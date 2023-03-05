package com.tyron.builder.gradle.internal.manifest

import com.tyron.builder.gradle.internal.services.ProjectServices
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.util.function.BooleanSupplier

/**
 * a lazy manifest parser that can create a `Provider<ManifestData>`
 */
class LazyManifestParser(
    private val manifestFile: Provider<RegularFile>,
    private val manifestFileRequired: Boolean,
    private val projectServices: ProjectServices,
    private val manifestParsingAllowed: BooleanSupplier
): ManifestDataProvider {

     override val manifestData: Provider<ManifestData> by lazy {
        // using map will allow us to keep task dependency should the manifest be generated or
        // transformed via a task
        val provider = manifestFile.map {
            parseManifest(
                it.asFile,
                manifestFileRequired,
                manifestParsingAllowed,
                projectServices.issueReporter
            )
        }

        // wrap the provider in a property to allow memoization
        projectServices.objectFactory.property(ManifestData::class.java).also {
            it.set(provider)
            it.finalizeValueOnRead()
            // TODO disable early get
        }
    }

    override val manifestLocation: String
        get() = manifestFile.get().asFile.absolutePath
}
