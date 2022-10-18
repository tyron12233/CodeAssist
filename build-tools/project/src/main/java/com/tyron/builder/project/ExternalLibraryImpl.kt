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
data class ExternalLibraryImpl(
    override val address: String,

    /**
     * Path to the .aar file on the filesystem, if known and one exists.
     *
     * The IDE doesn't work with AAR files and instead relies on the build system to extract
     * necessary files to disk. Location of the original AAR files is not always known, and some
     * [ExternalAndroidLibrary] instances point to folders that never came from an AAR. In such cases, this
     * attribute is null.
     */
    override val location: PathString? = null,

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
    override val manifestFile: PathString? = null,

    /**
     * Java package name for the resources in this library.
     */
    override val packageName: String? = null,

    /**
     * Path to the folder containing unzipped, plain-text, non-namespaced resources. Or null
     * for libraries that contain no resources.
     */
    override val resFolder: ResourceFolder? = null,

    /**
     * Path to the folder containing assets. Or null for libraries that contain no assets.
     */
    override val assetsFolder: PathString? = null,

    /**
     * Path to the symbol file (`R.txt`) containing information necessary to generate the
     * non-namespaced R class for this library. Null if no such file exists.
     */
    override val symbolFile: PathString? = null,

    /**
     * Path to the aapt static library (`res.apk`) containing namespaced resources in proto format.
     *
     * This is only known for "AARv2" files, built from namespaced sources.
     */
    override val resApkFile: PathString? = null
): ExternalAndroidLibrary {
    /**
     * Constructs a new [ExternalAndroidLibrary] with the given address and all other values set to their defaults. Intended to
     * simplify construction from Java.
     */
    constructor(address: String) : this(address, null)

    /**
     * Returns true if this [ExternalAndroidLibrary] contributes any resources. Resources may be packaged
     * as either a res.apk file or a res folder.
     */
    override val hasResources get() = resApkFile != null || resFolder != null

    /**
     * Returns a copy of the receiver with the given manifest file. Intended to simplify construction from Java.
     */
    fun withManifestFile(path: PathString?) = copy(manifestFile = path)

    /**
     * Returns a copy of the receiver with the given res folder. Intended to simplify construction from Java.
     */
    fun withResFolder(path: ResourceFolder?) = copy(resFolder = path)

    /**
     * Returns a copy of the receiver with the given location. Intended to simplify construction from Java.
     */
    fun withLocation(path: PathString?) = copy(location = path)

    /**
     * Returns a copy of the receiver with the given symbol file. Intended to simplify construction from Java.
     */
    fun withSymbolFile(path: PathString?) = copy(symbolFile = path)

    /**
     * Returns a copy of the receiver with the given [packageName]. Intended to simplify construction from Java.
     */
    fun withPackageName(packageName: String?) = copy(packageName = packageName)

    /**
     * Returns true iff this [Library] contains no files
     */
    fun isEmpty() = this == ExternalLibraryImpl(address = address, packageName = packageName)
}
