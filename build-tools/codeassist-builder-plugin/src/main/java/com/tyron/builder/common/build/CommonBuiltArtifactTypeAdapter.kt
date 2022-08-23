package com.tyron.builder.common.build

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

/**
 * Common behaviors for loading and saving [T] to a json file.
 *
 * @param T the in memory representation of the output.json file.
 */
abstract class CommonBuiltArtifactTypeAdapter<T: CommonBuiltArtifact>: TypeAdapter<T>() {

    /**
     * Subclasses should use this hook to write their specific attributes to the
     * json output stream.
     *
     * @param out the json writer to write to.
     * @param value the instance to serialize to json.
     */
    abstract fun writeSpecificAttributes(out: JsonWriter, value: T)

    @Throws(IOException::class)
    override fun write(out: JsonWriter, value: T?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.beginObject()
        writeSpecificAttributes(out, value)
        value.versionCode?.let {
            out.name("versionCode").value(it)
        }
        value.versionName?.let {
            out.name("versionName").value(it)
        }
        out.name("outputFile").value(value.outputFile)
        out.endObject()
    }

    /**
     * Common reading behaviors for reading all attributes belonging to [CommonBuiltArtifact].
     *
     * Subclasses specific attributes should be handled by the [handleAttribute] lambda which
     * will be called for any attributes this method does not know about.
     *
     * Instantiation of the [T] object will be handled by the [instantiate] lambda that will be
     * passed with all the parsed attributes as intrinsic types.
     *
     * @param handleAttribute lambda to handle attributes unknown to this method.
     * @param instantiate lambda to instantiate [T] with the parsed attributes.
     */
    @Throws(IOException::class)
    fun read(
        reader: JsonReader,
        handleAttribute: (attributeName: String) -> Unit,
        instantiate: (
            outputFile: String,
            versionCode: Int,
            versionName: String
        ) -> T
    ): T {

        reader.beginObject()
        var versionCode = 0
        var versionName: String? = null
        var outputFile: String? = null
        var attributes: ImmutableMap<String, String> = ImmutableMap.of()

        while (reader.hasNext()) {
            when (val attributeName = reader.nextName()) {
                // keep reading properties for backward compatibility but ignore it.
                "properties" -> readProperties(reader, ImmutableMap.Builder<String, String>())
                "versionCode" -> versionCode = reader.nextInt()
                "versionName" -> versionName = reader.nextString()
                "outputFile" -> outputFile = reader.nextString()
                else -> handleAttribute(attributeName)
            }
        }
        reader.endObject()

        return instantiate(
            outputFile!!,
            versionCode,
            versionName.orEmpty()
        )

    }

    @Throws(IOException::class)
    private fun readProperties(reader: JsonReader, properties: ImmutableMap.Builder<String, String>) {

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
            if (key != null) {
                properties.put(key, value.orEmpty())
            }
            reader.endObject()
        }
        reader.endArray()
    }
}

internal object GenericBuiltArtifactTypeAdapter: CommonBuiltArtifactTypeAdapter<GenericBuiltArtifact>() {

    override fun writeSpecificAttributes(out: JsonWriter, value: GenericBuiltArtifact) {
        out.name("type").value(value.outputType)
        out.name("filters").beginArray()
        for (filter in value.filters) {
            out.beginObject()
            out.name("filterType").value(filter.filterType)
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
    override fun read(reader: JsonReader): GenericBuiltArtifact {
        var outputType: String? = null
        val filters = ImmutableList.Builder<GenericFilterConfiguration>()
        val attributes = mutableMapOf<String, String>()
        return super.read(reader,
            { attributeName: String ->
                when(attributeName) {
                    "type" -> outputType = reader.nextString()
                    "filters" -> readFilters(reader, filters)
                    "attributes" -> readAttributes(reader, attributes)
                    // any other attribute we do not know about is AGP implementation details
                    // we do not care about. it has to be a String though.
                    else -> reader.nextString()
                }
            },
            { outputFile: String,
                versionCode: Int,
                versionName: String ->
                GenericBuiltArtifact(
                    outputType = outputType.orEmpty(),
                    filters = filters.build(),
                    outputFile = outputFile,
                    versionCode = versionCode,
                    versionName = versionName,
                    attributes = attributes
                )
            })
    }

    @Throws(IOException::class)
    private fun readFilters(reader: JsonReader, filters: ImmutableList.Builder<GenericFilterConfiguration>) {

        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var filterType: String? = null
            var value: String? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "filterType" -> filterType = reader.nextString()
                    "value" -> value = reader.nextString()
                }
            }
            if (filterType != null && value != null) {
                filters.add(GenericFilterConfiguration(filterType, value))
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