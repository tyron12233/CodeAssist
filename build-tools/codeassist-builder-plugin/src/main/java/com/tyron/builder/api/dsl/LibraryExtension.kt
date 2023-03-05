package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.NamedDomainObjectContainer

/**
 * Extension for the Android Library Gradle Plugin.
 *
 * This is the `android` block when the `com.android.library` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
 */
interface LibraryExtension :
    CommonExtension<
        LibraryBuildFeatures,
        LibraryBuildType,
        LibraryDefaultConfig,
        LibraryProductFlavor>,
    TestedExtension {
    // TODO(b/140406102)

    /** Aidl files to package in the aar. */
    @get:Incubating
    val aidlPackagedList: MutableCollection<String>?

    /**
     * container of Prefab options
     */
    @get:Incubating
    val prefab: NamedDomainObjectContainer<PrefabPackagingOptions>

    /**
     * Customizes publishing build variant artifacts from library module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [LibraryPublishing]
     */
    val publishing: LibraryPublishing

    /**
     * Customizes publishing build variant artifacts from library module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [LibraryPublishing]
     */
    fun publishing(action: LibraryPublishing.() -> Unit)
}