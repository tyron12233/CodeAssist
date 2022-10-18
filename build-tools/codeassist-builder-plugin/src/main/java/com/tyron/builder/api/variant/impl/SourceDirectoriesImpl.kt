package com.tyron.builder.api.variant.impl

import com.android.SdkConstants
import com.tyron.builder.api.variant.SourceDirectories
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.tasks.util.PatternSet
import java.io.File

abstract class SourceDirectoriesImpl(
    private val _name: String,
    private val variantServices: VariantServices,
    private val variantDslFilters: PatternFilterable?
): SourceDirectories {

    /**
     * Filters to use for the variant source folders only.
     * This will be initialized from the variant DSL source folder filters if it exists or empty
     * if it does not.
     */
    val filter = PatternSet().also {
        if (variantDslFilters != null) {
            it.setIncludes(variantDslFilters.includes)
            it.setExcludes(variantDslFilters.excludes)
        }
    }

    override fun <T : Task> addGeneratedSourceDirectory(taskProvider: TaskProvider<T>, wiredWith: (T) -> DirectoryProperty) {
        val mappedValue: Provider<Directory> = taskProvider.flatMap {
            wiredWith(it)
        }
        taskProvider.configure { task ->
            wiredWith.invoke(task).set(
                variantServices.projectInfo.buildDirectory.dir("${SdkConstants.FD_GENERATED}/$_name/${taskProvider.name}")
            )
        }
        addSource(
            TaskProviderBasedDirectoryEntryImpl(
                "$_name-${taskProvider.name}",
                mappedValue,
                isUserAdded = true,
            )
        )
    }

    override fun getName(): String = _name

    override fun addStaticSourceDirectory(srcDir: String) {
        val directory = variantServices.projectInfo.projectDirectory.dir(srcDir)
        if (!directory.asFile.exists() || !directory.asFile.isDirectory) {
            throw IllegalArgumentException("$srcDir does not point to a directory")
        }
        addSource(
            FileBasedDirectoryEntryImpl(
                name = "variant",
                directory = directory.asFile,
                filter = filter,
                isUserAdded = true
            )
        )
    }

    internal abstract fun addSource(directoryEntry: DirectoryEntry)

    abstract fun variantSourcesForModel(filter: (DirectoryEntry) -> Boolean ): List<File>
}