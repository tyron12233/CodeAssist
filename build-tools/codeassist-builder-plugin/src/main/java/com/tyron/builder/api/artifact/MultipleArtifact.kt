package com.tyron.builder.api.artifact

import org.gradle.api.Incubating
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile

/**
 * Public [Artifact] for Android Gradle plugin.
 *
 * This type inherits [Artifact.Multiple]. For single artifacts, see [SingleArtifact].
 *
 * All methods in [Artifacts] should be supported with any subclass of this
 * class.
 */
sealed class MultipleArtifact<FileTypeT : FileSystemLocation>(
    kind: ArtifactKind<FileTypeT>,
    category: Category =  Category.INTERMEDIATES,
) : Artifact.Multiple<FileTypeT>(kind, category) {

    /**
     * Text files with additional ProGuard rules to be used to determine which classes are compiled
     * into the main dex file.
     *
     * If set, rules from these files are used in combination with the default rules used by the
     * build system.
     *
     * Initialized from DSL [com.android.build.api.dsl.VariantDimension.multiDexKeepProguard]
     */
    object MULTIDEX_KEEP_PROGUARD:
            MultipleArtifact<RegularFile>(FILE, Category.SOURCES),
            Replaceable,
            Transformable

    /**
     * This artifact type is deprecated, use [Artifacts.forScope] API instead.
     */
    @Deprecated(
        message = "Use Artifacts.forScope APIs.",
    )
    object ALL_CLASSES_DIRS:
        MultipleArtifact<Directory>(DIRECTORY),
        Appendable,
        Replaceable,
        Transformable

    /**
     * This artifact type is deprecated, use [Artifacts.forScope] API instead.
     */
    @Deprecated(
        message = "Use Artifacts.forScope APIs.",
    )
    object ALL_CLASSES_JARS:
        MultipleArtifact<RegularFile>(FILE),
        Appendable,
        Replaceable,
        Transformable
}