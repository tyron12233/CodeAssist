package com.tyron.builder.project

import com.android.ide.common.util.PathString
import com.android.projectmodel.ResourceFolder

/**
 * Represents a dependency on an external library. External libraries are folders containing
 * some combination of prebuilt classes, resources, and manifest file. Although android
 * libraries are normally packaged in an AAR file, the actual dependency is on the folder where the
 * AAR would have been extracted by the build system rather than the AAR file itself. If the library
 * came from an AAR file, the build system would extract it somewhere and then provide an instance
 * of [ExternalAndroidLibrary] describing the contents and location of the extracted folder.
 */
interface ExternalAndroidLibrary {
    val address: String

    /**
     * Path to the .aar file on the filesystem, if known and one exists.
     *
     * The IDE doesn't work with AAR files and instead relies on the build system to extract
     * necessary files to disk. Location of the original AAR files is not always known, and some
     * [ExternalAndroidLibrary] instances point to folders that never came from an AAR. In such cases, this
     * attribute is null.
     */
    val location: PathString?

    /**
     * Location of the manifest file for this library. This manifest contains sufficient information
     * to understand the library and its contents are intended to be merged into any application
     * that uses the library.
     *
     * Any library that contains resources must either provide a [manifestFile] or a [packageName].
     * Not all libraries include a manifest. For example, some libraries may not contain resources.
     * Other libraries may contain resources but use some other mechanism to inform the build system
     * of the package name. The latter will fill in [packageName] rather than providing a
     * [manifestFile].
     */
    val manifestFile: PathString?

    /**
     * Java package name for the resources in this library.
     */
    val packageName: String?

    /**
     * Path to the folder containing unzipped, plain-text, non-namespaced resources. Or null
     * for libraries that contain no resources.
     */
    val resFolder: ResourceFolder?

    /**
     * Path to the folder containing assets. Or null for libraries that contain no assets.
     */
    val assetsFolder: PathString?

    /**
     * Path to the symbol file (`R.txt`) containing information necessary to generate the
     * non-namespaced R class for this library. Null if no such file exists.
     */
    val symbolFile: PathString?

    /**
     * Path to the aapt static library (`res.apk`) containing namespaced resources in proto format.
     *
     * This is only known for "AARv2" files, built from namespaced sources.
     */
    val resApkFile: PathString?

    /**
     * Returns true if this [ExternalAndroidLibrary] contributes any resources. Resources may be packaged
     * as either a res.apk file or a res folder.
     */
    val hasResources : Boolean

    /**
     * Returns a library name which can be used as library identifier and displayed to the user.
     */
    @JvmDefault
    fun libraryName(): String = location?.fileName ?: address
}