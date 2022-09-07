package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.BundleConfig
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

class BundleConfigImpl(
    override val codeTransparency: CodeTransparencyImpl,
    val variantServices: VariantServices,
): BundleConfig {

    internal val metadataFiles = variantServices.listPropertyOf(MetadataRecord::class.java) {}

    override fun addMetadataFile(metadataDirectory: String, file: Provider<RegularFile>) {
        metadataFiles.add(
            MetadataRecord(
                metadataDirectory,
                file
            )
        )
    }
}
