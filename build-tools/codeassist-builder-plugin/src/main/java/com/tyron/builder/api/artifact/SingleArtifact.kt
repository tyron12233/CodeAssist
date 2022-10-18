package com.tyron.builder.api.artifact

import org.gradle.api.Incubating
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile

/**
 * Public [Artifact] for Android Gradle plugin.
 *
 * These are [Artifact.Single], see [MultipleArtifact] for multiple ones.
 *
 * All methods in the [Artifacts] class should be supported with any subclass of this
 * class.
 */
sealed class SingleArtifact<T : FileSystemLocation>(
    kind: ArtifactKind<T>,
    category: Category = Category.INTERMEDIATES,
    private val fileName: String? = null
)
    : Artifact.Single<T>(kind, category) {

    override fun getFileSystemLocationName(): String {
        return fileName ?: ""
    }
    /**
     * Directory where APK files will be located. Some builds can be optimized for testing when
     * invoked from Android Studio. In such cases, the APKs are not suitable for deployment to
     * Play Store.
     */
    object APK:
        SingleArtifact<Directory>(DIRECTORY),
        ContainsMany,
        Replaceable,
        Transformable


    /**
     * Merged manifest file that will be used in the APK, Bundle and InstantApp packages.
     * This will only be available on modules applying one of the following plugins :
     *      com.android.application
     *      com.android.dynamic-feature
     *      com.android.library
     *      com.android.test
     *
     * For each module, unit test and android test variants will not have a manifest file
     * available.
     */
    object MERGED_MANIFEST:
        SingleArtifact<RegularFile>(FILE, Category.INTERMEDIATES, "AndroidManifest.xml"),
        Replaceable,
        Transformable

    object OBFUSCATION_MAPPING_FILE:
        SingleArtifact<RegularFile>(FILE, Category.OUTPUTS, "mapping.txt") {
            override fun getFolderName(): String = "mapping"
        }

    /**
     * The final Bundle ready for consumption at Play Store.
     * This is only valid for the base module.
     */
    object BUNDLE:
        SingleArtifact<RegularFile>(FILE, Category.OUTPUTS),
        Transformable

    /**
     * The final AAR file as it would be published.
     */
    object AAR:
        SingleArtifact<RegularFile>(FILE, Category.OUTPUTS),
        Transformable

    /**
     * A file containing the list of public resources exported by a library project.
     *
     * It will have one resource per line and be of the format
     * `<resource-type> <resource-name>`
     *
     * for example
     * ```
     * string public_string
     * ```
     *
     * This file will always be created, even if there are no resources.
     *
     * See [Choose resources to make public](https://developer.android.com/studio/projects/android-library.html#PrivateResources).
     */
    object PUBLIC_ANDROID_RESOURCES_LIST: SingleArtifact<RegularFile>(FILE)

    /**
     * The metadata for the library dependencies.
     *
     * Format of the file is described by com.android.tools.build.libraries.metadata.AppDependencies
     * which is not guaranteed to be stable.
     */

    @Incubating
    object METADATA_LIBRARY_DEPENDENCIES_REPORT: SingleArtifact<RegularFile>(FILE),
        Replaceable,
        Transformable


    /**
     * Assets that will be packaged in the resulting APK or Bundle.
     *
     * When used as an input, the content will be the merged assets.
     * For the APK, the assets will be compressed before packaging.
     *
     * To add new folders to [ASSETS], you must use [com.android.build.api.variant.Sources.assets]
     */
    @Incubating
    object ASSETS:
        SingleArtifact<Directory>(DIRECTORY),
        Replaceable,
        Transformable

    /**
     *  Universal APK that contains assets for all screen densities.
     *  It is not optimized for particular phone and is much bigger than regular APKs.
     *  Build creates a bundle file first and then generates Universal APK from it.
     *
     *  It's <i>not efficient</i> to use [APK_FROM_BUNDLE] because of size and because
     *  it creates a Bundle (.aab) file first and finally extracts the APK from it.
     *  These steps will slow your build flow. Thus, unless your intent is to
     *  check the universal APK as produced from a .aab file, prefer [APK].
     */
    @Incubating
    object APK_FROM_BUNDLE:
        SingleArtifact<RegularFile>(FILE, Category.OUTPUTS),
        Transformable
}