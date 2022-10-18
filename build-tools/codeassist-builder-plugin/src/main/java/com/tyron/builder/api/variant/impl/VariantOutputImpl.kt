package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.FilterConfiguration
import com.tyron.builder.api.variant.VariantOutput
import com.tyron.builder.api.variant.VariantOutputConfiguration
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import java.io.File
import java.io.Serializable

data class VariantOutputImpl(
    @get:Input
    @get:Optional
    override val versionCode: Property<Int?>,
    @get:Input
    @get:Optional
    override val versionName: Property<String?>,
    @get:Input
    override val enabled: Property<Boolean>,

    @get:Internal
    val variantOutputConfiguration: VariantOutputConfiguration,

    // private APG APIs.
    @get:Input
    val baseName: String,
    @get:Input
    val fullName: String,
    @get:Input
    val outputFileName: Property<String>
) : VariantOutput, VariantOutputConfiguration {

    @get:Internal
    override val enable = enabled

    data class SerializedForm(
        @get:Input
        val versionCode: Int?,
        @get:Input
        val versionName: String?,
        @get:Nested
        val variantOutputConfiguration: VariantOutputConfigurationImpl,
        @get:Input
        val baseName: String,
        @get:Input
        val fullName: String,
        @get:Input
        val outputFileName: String): Serializable {

        fun toBuiltArtifact(outputFile: File): BuiltArtifactImpl =
            BuiltArtifactImpl.make(
                outputFile = outputFile.absolutePath,
                versionCode = versionCode,
                versionName = versionName,
                variantOutputConfiguration = variantOutputConfiguration
            )
    }

    fun toBuiltArtifact(outputFile: File): BuiltArtifactImpl =
        BuiltArtifactImpl.make(
            outputFile = outputFile.absolutePath,
            versionCode = versionCode.orNull,
            versionName = versionName.orNull,
            variantOutputConfiguration = variantOutputConfiguration
        )

    fun toSerializedForm() = SerializedForm(
        versionCode = versionCode.orNull,
        versionName = versionName.orNull,
        variantOutputConfiguration = variantOutputConfiguration as VariantOutputConfigurationImpl,
        fullName = fullName,
        baseName = baseName,
        outputFileName = outputFileName.get())

    fun getFilter(filterType: FilterConfiguration.FilterType): FilterConfiguration? =
        filters.firstOrNull { it.filterType == filterType }

    @get:Input
    override val outputType: VariantOutputConfiguration.OutputType
        get() = variantOutputConfiguration.outputType

    @get:Nested
    override val filters: Collection<FilterConfigurationImpl>
        get() = (variantOutputConfiguration as VariantOutputConfigurationImpl).filters
}
