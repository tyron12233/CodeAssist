package com.tyron.builder.common.build

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

/**
 * Common behaviors for loading and saving [CommonBuiltArtifacts] subclass [T] to a json file.
 */
abstract class CommonBuiltArtifactsTypeAdapter<
        T: CommonBuiltArtifacts,
        ArtifactTypeT,
        ElementT,
        >: TypeAdapter<T>() {

    abstract val artifactTypeTypeAdapter: TypeAdapter<ArtifactTypeT>
    abstract val elementTypeAdapter: TypeAdapter<ElementT>
    abstract fun getArtifactType(artifacts: T): ArtifactTypeT
    abstract fun getElements(artifacts: T): Collection<ElementT>
    abstract fun getElementType(artifacts: T): String?

    final override fun write(out: JsonWriter, value: T?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.beginObject()
        out.name("version").value(value.version)
        out.name("artifactType")
        artifactTypeTypeAdapter.write(out, getArtifactType(value))
        out.name("applicationId").value(value.applicationId)
        out.name("variantName").value(value.variantName)
        out.name("elements").beginArray()
        for (element in getElements(value)) {
            elementTypeAdapter.write(out, element)
        }
        out.endArray()
        getElementType(value)?.let {elementType ->
            out.name("elementType").value(elementType)
        }
        out.endObject()
    }


    abstract fun instantiate(
        version: Int,
        artifactType: ArtifactTypeT,
        applicationId: String,
        variantName: String,
        elements: List<ElementT>,
        elementType: String?,
    ) : T

    final override fun read(reader: JsonReader): T {
        reader.beginObject()
        var version: Int? = null
        var artifactType: ArtifactTypeT? = null
        var applicationId: String? = null
        var variantName: String? = null
        val elements = mutableListOf<ElementT>()
        var elementType: String? = null

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "version" -> version = reader.nextInt()
                "artifactType" -> artifactType = artifactTypeTypeAdapter.read(reader)
                "applicationId" -> applicationId = reader.nextString()
                "variantName" -> variantName = reader.nextString()
                "elements" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        elements.add(elementTypeAdapter.read(reader))
                    }
                    reader.endArray()
                }
                "elementType" -> elementType = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return instantiate(
            version ?: throw IOException("version is required"),
            artifactType ?: throw IOException("artifactType is required"),
            applicationId  ?: throw IOException("applicationId is required"),
            variantName ?: throw IOException("variantName is required"),
            elements,
            elementType
        )

    }
}

object GenericArtifactTypeTypeAdapter: TypeAdapter<GenericArtifactType>() {

    override fun write(writer: JsonWriter, type: GenericArtifactType) {
        writer.beginObject()
        writer.name("type").value(type.type)
        writer.name("kind").value(type.kind)
        writer.endObject()
    }

    override fun read(reader: JsonReader): GenericArtifactType {
        var type: String? = null
        var kind: String? = null
        reader.beginObject()
        while(reader.hasNext()) {
            when(val name = reader.nextName()) {
                "type" -> type = reader.nextString()
                "kind" -> kind = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return GenericArtifactType(
            type = type ?: throw IOException("artifactType.type is required"),
            kind = kind ?: throw IOException("artifactType.kind is required"),
        )
    }
}

object GenericBuiltArtifactsTypeAdapter: CommonBuiltArtifactsTypeAdapter<
        GenericBuiltArtifacts,
        GenericArtifactType,
        GenericBuiltArtifact,
        >() {

    override val artifactTypeTypeAdapter get() = GenericArtifactTypeTypeAdapter
    override val elementTypeAdapter: TypeAdapter<GenericBuiltArtifact>
        get() = GenericBuiltArtifactTypeAdapter
    override fun getArtifactType(artifacts: GenericBuiltArtifacts) = artifacts.artifactType
    override fun getElements(artifacts: GenericBuiltArtifacts) = artifacts.elements
    override fun getElementType(artifacts: GenericBuiltArtifacts): String? = artifacts.elementType

    override fun instantiate(
        version: Int,
        artifactType: GenericArtifactType,
        applicationId: String,
        variantName: String,
        elements: List<GenericBuiltArtifact>,
        elementType: String?
    ) = GenericBuiltArtifacts(
        version = version,
        artifactType = artifactType,
        applicationId = applicationId,
        variantName = variantName,
        elements = elements,
        elementType = elementType,
    )
}