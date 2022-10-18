package com.tyron.builder.api.component.impl

import com.tyron.builder.api.variant.impl.DirectoryEntries
import com.tyron.builder.api.variant.impl.DirectoryEntry
import com.tyron.builder.api.variant.impl.FlatSourceDirectoriesImpl
import com.tyron.builder.api.variant.impl.LayeredSourceDirectoriesImpl

/**
 * Interface to calculate the default list of sources [DirectoryEntry] per source type.
 */
interface DefaultSourcesProvider {

    /**
     * the list of sources [DirectoryEntry] for java
     */
    fun getJava(lateAdditionsDelegate: FlatSourceDirectoriesImpl): List<DirectoryEntry>

    /**
     * the list of sources [DirectoryEntry] for kotlin
     */
    fun getKotlin(lateAdditionsDelegate: FlatSourceDirectoriesImpl): List<DirectoryEntry>

    /**
     * the list of sources [DirectoryEntries] for android resources.
     *
     * The [List] is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on.
     */
    fun getRes(lateAdditionsDelegate: LayeredSourceDirectoriesImpl): List<DirectoryEntries>

    /**
     * the list of sources [DirectoryEntry] for java resources.
     */
    fun getResources(lateAdditionsDelegate: FlatSourceDirectoriesImpl): List<DirectoryEntry>

    /**
     * the list of [DirectoryEntries] for assets.
     *
     * The [List] is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on.
     */
    fun getAssets(lateAdditionsDelegate: LayeredSourceDirectoriesImpl): List<DirectoryEntries>

    /**
     * the list of [DirectoryEntries] for jni libraries.
     *
     * The [List] is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on.
     */
    fun getJniLibs(lateAdditionsDelegate: LayeredSourceDirectoriesImpl): List<DirectoryEntries>

    /**
     * the list of [DirectoryEntries] for shaders or null if the feature is disabled.
     *
     * The [List] is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on.
     */
    fun getShaders(lateAdditionsDelegate: LayeredSourceDirectoriesImpl): List<DirectoryEntries>?

    /**
     * the list of sources [DirectoryEntry] for AIDL or null if the feature is disabled.
     */
    fun getAidl(lateAdditionsDelegate: FlatSourceDirectoriesImpl): List<DirectoryEntry>?

    /**
     * the list of [DirectoryEntries] for machine learning models.
     *
     * The [List] is ordered in ascending order of importance, meaning the first set is meant to be
     * overridden by the 2nd one and so on.
     */
    fun getMlModels(lateAdditionsDelegate: LayeredSourceDirectoriesImpl): List<DirectoryEntries>

    /**
     * the list of sources [DirectoryEntry] for renderscript or null if the feature is disabled.
     */
    fun getRenderscript(lateAdditionsDelegate: FlatSourceDirectoriesImpl): List<DirectoryEntry>?
}
