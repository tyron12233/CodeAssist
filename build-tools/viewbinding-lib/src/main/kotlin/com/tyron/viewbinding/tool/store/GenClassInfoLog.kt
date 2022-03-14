package com.tyron.viewbinding.tool.store

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.InputStream

/**
 * includes the mapping from key to the generated binding class and its variables.
 * e.g: generic_view to android.databinding.testapp.databinding.GenericViewBinding
 */
data class GenClassInfoLog(
    @SerializedName("mappings")
    private val mappings: MutableMap<String, GenClass> = mutableMapOf()) {

    fun mappings(): Map<String, GenClass> = mappings

    companion object {
        private val GSON = GsonBuilder()
            .disableHtmlEscaping()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting().create()

        @JvmStatic
        fun fromFile(file: File): GenClassInfoLog {
            if (!file.exists()) {
                return GenClassInfoLog()
            }
            return file.reader(Charsets.UTF_16).use {
                GSON.fromJson(it, GenClassInfoLog::class.java)
            }
        }

        @JvmStatic
        fun fromInputStream(inputStream : InputStream): GenClassInfoLog {
            return inputStream.reader(Charsets.UTF_16).use {
                GSON.fromJson(it, GenClassInfoLog::class.java)
            }
        }
    }

    /**
     * creates a sub info that has classes only in the given module package.
     * note that it is not the package if the generated class, it is the package of the module
     * where the class originated from.
     */
    fun createPackageInfoLog(pkg : String) : GenClassInfoLog {
        val infoLog = GenClassInfoLog()
        mappings.asSequence().filter {
            it.value.modulePackage == pkg
        }.forEach {
            infoLog.addMapping(it.key, it.value)
        }
        return infoLog
    }

    fun addAll(other: GenClassInfoLog) {
        other.mappings.forEach {
            addMapping(it.key, it.value)
        }
    }

    fun addMapping(infoFileName: String, klass: GenClass) {
        mappings[infoFileName] = klass
    }

    fun diff(other : GenClassInfoLog) : Set<String> {
        // find diffs w/ the other one.
        val diff = mutableSetOf<String>()
        other.mappings.forEach {
            if (mappings[it.key] == null || mappings[it.key] != it.value) {
                diff.add(it.key)
            }
        }
        mappings.forEach {
            if (other.mappings[it.key] == null || other.mappings[it.key] != it.value) {
                diff.add(it.key)
            }
        }
        return diff
    }

    fun serialize(file: File) {
        if (file.exists()) {
            file.delete()
        }
        file.writer(Charsets.UTF_16).use {
            GSON.toJson(this, it)
        }
    }

    /**
     * holds the signature for a class. We only care about the class name and its variables.
     */
    data class GenClass(
        @SerializedName("qualified_name")
        val qName: String,
        @SerializedName("module_package")
        val modulePackage : String,
        @SerializedName("variables") //  var name -> type
        val variables: Map<String, String>,
        val implementations : Set<GenClassImpl>
    )

    data class GenClassImpl(
        @SerializedName("tag")
        val tag : String,
        @SerializedName("merge")
        val merge : Boolean,
        @SerializedName("qualified_name")
        val qualifiedName: String
    ) {
        companion object {
            fun from(bundle :ResourceBundle.LayoutFileBundle) =
                GenClassImpl(
                    tag = bundle.createTag(),
                    merge = bundle.isMerge,
                    qualifiedName = bundle.bindingClassPackage + "." +
                            bundle.createImplClassNameWithConfig()
                )
        }
    }
}
