package com.tyron.builder.api.artifact

import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import java.util.Locale
import java.io.Serializable

/**
 * Defines a type of artifact handled by the Android Gradle Plugin.
 *
 * Each instance of [Artifact] is produced by a [org.gradle.api.Task] and potentially consumed by
 * any number of tasks.
 *
 * An artifact can potentially be produced by more than one task (each task acting in an additive
 * behavior), but consumers must be aware when more than one artifact can be present,
 * implementing the [Multiple] interface will indicate such requirement.
 *
 * An artifact must be one of the supported [ArtifactKind] and must be provided when the constructor is called.
 * ArtifactKind also defines the specific [FileSystemLocation] subclass used.
 */
abstract class Artifact<T: FileSystemLocation>(
    val kind: ArtifactKind<T>,
    val category: Category
) : Serializable {

    /**
     * Provide a unique name for the artifact type. For external plugins defining new types,
     * consider adding the plugin name to the artifact's name to avoid collision with other plugins.
     */
    fun name(): String = javaClass.simpleName

    /**
     * @return The folder name under which the artifact files or folders should be stored.
     */
    open fun getFolderName(): String = name().lowercase()

    /**
     * @return Depending on [T], returns the file name of the folder under the variant-specific folder or
     * an empty string to use defaults.
     */
    open fun getFileSystemLocationName(): String = ""

    /**
     * Supported [ArtifactKind]
     */
    companion object {
        /**
         * [ArtifactKind] for [RegularFile]
         */
        @JvmField
        val FILE = ArtifactKind.FILE

        /**
         * [ArtifactKind] for [Directory]
         */
        @JvmField
        val DIRECTORY = ArtifactKind.DIRECTORY
    }

    /**
     * Defines the kind of artifact type. this will be used to determine the output file location
     * for instance.
     */
    enum class Category {
        /* Source artifacts */
        SOURCES,
        /* Generated files that are meant to be visible to users from the IDE */
        GENERATED,
        /* Intermediates files produced by tasks. */
        INTERMEDIATES,
        /* output files going into the outputs folder. This is the result of the build. */
        OUTPUTS,
        /* Report files for tests and lint. */
        REPORTS,
        ;
    }

    /**
     * Denotes possible multiple [FileSystemLocation] instances for this artifact type.
     * Consumers of artifact types with multiple instances must consume a collection of
     * [FileSystemLocation].
     */
    abstract class Multiple<FileTypeT: FileSystemLocation>(
        kind: ArtifactKind<FileTypeT>,
        category: Category
    ) : Artifact<FileTypeT>(kind, category)


    /**
     * Denotes a single [FileSystemLocation] instance of this artifact type at a given time.
     * Single artifact types can be transformed or replaced but never appended.
     */
    abstract class Single<FileTypeT: FileSystemLocation>(
        kind: ArtifactKind<FileTypeT>,
        category: Category
    ) : Artifact<FileTypeT>(kind, category)

    /**
     * Denotes a single [DIRECTORY] that may contain zero to many
     * [com.android.build.api.variant.BuiltArtifact].
     *
     * Artifact types annotated with this marker interface are backed up by a [DIRECTORY] whose
     * content should be read using the [com.android.build.api.variant.BuiltArtifactsLoader].
     *
     * If producing an artifact type annotated with this marker interface, content should be
     * written using the [com.android.build.api.variant.BuiltArtifacts.save] methods.
     */
    interface ContainsMany

    /**
     * Denotes an artifact type that can be appended to.
     * Appending means that existing artifacts produced by other tasks are untouched and a
     * new task producing the artifact type will have its output appended to the list of artifacts.
     *
     * Due to the additive behavior of the append scenario, an [Appendable] must be a
     * [Multiple].
     */
    interface Appendable

    /**
     * Denotes an artifact type that can transformed.
     *
     * Either a [Single] or [Multiple] artifact type can be transformed.
     */
    interface Transformable

    /**
     * Denotes an artifact type that can be replaced.
     * Only [Single] artifacts can be replaced, if you want to replace a [Multiple]
     * artifact type, you will need to transform it by combining all the inputs into a single output
     * instance.
     */
    interface Replaceable
}
