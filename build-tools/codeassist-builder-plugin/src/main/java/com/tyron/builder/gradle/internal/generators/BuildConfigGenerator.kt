package com.tyron.builder.gradle.internal.generators

import com.google.common.base.Charsets
import com.google.common.io.Closer
import com.squareup.javawriter.JavaWriter
import com.tyron.builder.api.variant.BuildConfigField
import com.tyron.builder.compiling.GeneratedCodeFileCreator
import java.io.*
import java.util.*
import javax.lang.model.element.Modifier

/**
 * Class able to generate a BuildConfig class in an Android project. The BuildConfig class contains
 * constants related to the build target.
 */
class BuildConfigGenerator(buildConfigData: BuildConfigData) : GeneratedCodeFileCreator {
    private val genFolder: String = buildConfigData.outputPath.toString()
    private val namespace: String = buildConfigData.namespace
    private val fields: Map<String, BuildConfigField<out Serializable>> = buildConfigData.buildConfigFields

    /** Returns a File representing where the BuildConfig class will be.  */
    override val folderPath =
        File(
            genFolder,
            namespace.replace('.', File.separatorChar)
        )

    override val generatedFilePath = File(folderPath, BUILD_CONFIG_NAME)

    /** Generates the BuildConfig class.  */
    @Throws(IOException::class)
    override fun generate() {
        if (!folderPath.isDirectory && !folderPath.mkdirs()) {
            throw RuntimeException("Failed to create " + folderPath.absolutePath)
        }
        Closer.create().use { closer ->
            val fos =
                closer.register(FileOutputStream(generatedFilePath))
            val out = closer.register(
                OutputStreamWriter(fos, Charsets.UTF_8)
            )
            val writer = closer.register(JavaWriter(out))
            writer.emitJavadoc("Automatically generated file. DO NOT MODIFY")
                .emitPackage(namespace)
                .beginType(
                    "BuildConfig",
                    "class",
                    PUBLIC_FINAL
                )
            for ((key, value) in fields) {
                value.emit(key, writer)
            }
            writer.endType()
        }
    }

    companion object {
        const val BUILD_CONFIG_NAME = "BuildConfig.java"
        private val PUBLIC_FINAL: Set<Modifier> =
            EnumSet.of(
                Modifier.PUBLIC,
                Modifier.FINAL
            )
    }
}
