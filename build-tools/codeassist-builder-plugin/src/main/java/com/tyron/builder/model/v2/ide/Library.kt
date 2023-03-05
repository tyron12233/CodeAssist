package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * Represent a variant/module/artifact dependency.
 */
interface Library: AndroidModel {
    /**
     * A Unique key representing the library, and allowing to match it with [GraphItem] instances
     */
    val key: String

    /**
     * The type of the dependency.
     */
    val type: LibraryType

    /**
     * Returns the project info to uniquely identify it (and its variant)
     *
     * Only valid for instances where [type] is [LibraryType.PROJECT]. It is null in other cases.
     */
    val projectInfo: ProjectInfo?

    /**
     * Returns the external library info to uniquely identify it (and its variant)
     *
     * Only valid for instances where [type] is [LibraryType.ANDROID_LIBRARY], or
     * [LibraryType.JAVA_LIBRARY]. It is null in other cases.
     */
    val libraryInfo: LibraryInfo?

    /**
     * The artifact location.
     *
     * Only valid for instances where [type] is [LibraryType.LIBRARY_JAVA] or
     * [LibraryType.ANDROID_LIBRARY]
     */
    val artifact: File?

    /**
     * The jar containing custom lint checks for consumers to use. This is filled by the
     * lintPublish configuration.
     *
     * The file may not exist.
     *
     * Only valid for instances where [type] is [LibraryType.ANDROID_LIBRARY]
     */
    val lintJar: File?

    /**
     * Data for libraries of type [LibraryType.ANDROID_LIBRARY]. It is null in other cases.
     */
    val androidLibraryData: AndroidLibraryData?
}
