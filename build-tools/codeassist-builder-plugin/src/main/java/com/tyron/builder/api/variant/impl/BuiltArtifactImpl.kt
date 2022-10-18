package com.tyron.builder.api.variant.impl

import com.google.common.collect.ImmutableList
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.variant.BuiltArtifact
import com.tyron.builder.api.variant.FilterConfiguration
import com.tyron.builder.api.variant.VariantOutputConfiguration
import com.tyron.builder.common.build.CommonBuiltArtifact
import com.tyron.builder.common.build.CommonBuiltArtifactTypeAdapter
import com.tyron.builder.gradle.internal.api.artifact.toArtifactType
import org.gradle.util.internal.GFileUtils
import java.io.IOException
import java.io.Serializable
import java.nio.file.Path

@Suppress("DataClassPrivateConstructor")
data class BuiltArtifactImpl private constructor(
    override val outputFile: String,
    override val versionCode: Int?,
    override val versionName: String?,
    val variantOutputConfiguration: VariantOutputConfiguration = VariantOutputConfigurationImpl(),
    val attributes: Map<String, String> = mapOf()
) : BuiltArtifact, CommonBuiltArtifact, Serializable, VariantOutputConfiguration by variantOutputConfiguration {

    fun newOutput(newOutputFile: Path): BuiltArtifactImpl {
        return make(
            outputFile = newOutputFile.toString(),
            versionCode = versionCode,
            versionName = versionName,
            variantOutputConfiguration = variantOutputConfiguration,
            attributes = attributes
        )
    }

    fun getFilter(filterType: FilterConfiguration.FilterType): FilterConfiguration? =
        filters.firstOrNull { it.filterType == filterType }

    companion object {

        @JvmStatic
        fun make(
            outputFile: String,
            versionCode: Int? = null,
            versionName: String? = null,
            variantOutputConfiguration: VariantOutputConfiguration = VariantOutputConfigurationImpl(),
            attributes: Map<String, String> = mapOf()
        )
                    = BuiltArtifactImpl(GFileUtils.toSystemIndependentPath(outputFile),
                versionCode,
                versionName,
                variantOutputConfiguration,
                attributes
        )

    }
}

internal object BuiltArtifactTypeAdapter: CommonBuiltArtifactTypeAdapter<BuiltArtifactImpl>() {

    override fun writeSpecificAttributes(out: JsonWriter, value: BuiltArtifactImpl) {
        out.name("type").value(value.outputType.toString())
        out.name("filters").beginArray()
        for (filter in value.filters) {
            out.beginObject()
            out.name("filterType").value(filter.filterType.toString())
            out.name("value").value(filter.identifier)
            out.endObject()
        }
        out.endArray()
        out.name("attributes").beginArray()
        for (attribute in value.attributes) {
            out.beginObject()
            out.name("key").value(attribute.key)
            out.name("value").value(attribute.value)
            out.endObject()
        }
        out.endArray()
    }

    @Throws(IOException::class)
    override fun read(reader: JsonReader): BuiltArtifactImpl {
        var outputType: String? = null
        val filters = ImmutableList.Builder<FilterConfigurationImpl>()
        val attributes = mutableMapOf<String, String>()
        return super.read(reader,
            { attributeName: String ->
                when(attributeName) {
                    "type" -> outputType = reader.nextString()
                    "filters" -> readFilters(reader, filters)
                    "attributes" -> readAttributes(reader, attributes)
                }
            },
            { outputFile: String,
                versionCode: Int,
                versionName: String ->
                BuiltArtifactImpl.make(
                    outputFile = outputFile,
                    versionCode = versionCode,
                    versionName = versionName,
                    variantOutputConfiguration =
                    VariantOutputConfigurationImpl(
                        isUniversal = VariantOutputConfiguration.OutputType.UNIVERSAL.name == outputType,
                        filters = filters.build()
                    ),
                    attributes = attributes
                )
            })
    }

    @Throws(IOException::class)
    private fun readFilters(reader: JsonReader, filters: ImmutableList.Builder<FilterConfigurationImpl>) {

        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var filterType: FilterConfiguration.FilterType? = null
            var value: String? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "filterType" -> filterType = FilterConfiguration.FilterType.valueOf(reader.nextString())
                    "value" -> value = reader.nextString()
                }
            }
            if (filterType != null && value != null) {
                filters.add(FilterConfigurationImpl(filterType, value))
            }
            reader.endObject()
        }
        reader.endArray()
    }

    @Throws(IOException::class)
    private fun readAttributes(reader: JsonReader, attributes: MutableMap<String, String>) {

        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var key: String? = null
            var value: String? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "key" -> key = reader.nextString()
                    "value" -> value = reader.nextString()
                }
            }
            if (key != null && value != null) {
                attributes[key] = value
            }
            reader.endObject()
        }
        reader.endArray()
    }
}

internal object ArtifactTypeTypeAdapter : TypeAdapter<Artifact<*>>() {

    @Throws(IOException::class)
    override fun write(out: JsonWriter, value: Artifact<*>) {
        out.beginObject()
        out.name("type").value(value.name())
        out.name("kind").value(value.kind.dataType().simpleName)
        out.endObject()
    }

    @Throws(IOException::class)
    override fun read(reader: JsonReader): Artifact<*> {
        reader.beginObject()
        var artifactType: Artifact<*>? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> artifactType = reader.nextString().toArtifactType()
                "kind" -> reader.nextString()
            }
        }
        reader.endObject()
        if (artifactType == null) {
            throw IOException("Invalid artifact type declaration")
        }
        return artifactType
    }
}