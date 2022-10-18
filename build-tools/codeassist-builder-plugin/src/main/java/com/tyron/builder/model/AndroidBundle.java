package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * Represents some of an {@link AndroidLibrary}.
 *
 * <p>The separation from AndroidLibrary is a historical artifact.
 */
public interface AndroidBundle extends Library {

    /**
     * Returns an optional configuration name if the library is output by a module
     * that publishes more than one variant.
     */
    @Nullable
    String getProjectVariant();

    /**
     * Returns the location of the dependency bundle.
     */
    @NotNull
    File getBundle();

    /**
     * Returns the location of the unzipped AAR folder.
     *
     * @deprecated Users of this model are strongly encouraged to migrate to using the methods for
     *     the individual artifacts within the AAR instead.
     */
    @Deprecated
    @NotNull
    File getFolder();

    /**
     * Returns the list of direct library dependencies of this dependency.
     * The order is important.
     */
    @NotNull
    List<? extends AndroidLibrary> getLibraryDependencies();

    /**
     * Returns the collection of external Jar files that are included in the dependency.
     * @return a list of JavaDependency. May be empty but not null.
     */
    @NotNull
    Collection<? extends JavaLibrary> getJavaDependencies();

    /**
     * Returns the location of the manifest.
     */
    @NotNull
    File getManifest();

    /**
     * Returns the location of the jar file to use for packaging.
     *
     * @return a File for the jar file. The file may not point to an existing file.
     * @see #getCompileJarFile()
     */
    @NotNull
    File getJarFile();

    /**
     * Returns the location of the jar file to use for compiling.
     *
     * @return a File for the jar file. The file may not point to an existing file.
     * @see #getJarFile()
     */
    @NotNull
    File getCompileJarFile();

    /**
     * Returns the location of the non-namespaced res folder.
     *
     * @return a File for the res folder. The file may not point to an existing folder.
     */
    @NotNull
    File getResFolder();

    /**
     * Returns the location of the namespaced resources static library (res.apk).
     *
     * @return the static library apk. Does not exist if the library is not namespaced. May be null
     *     in Android Gradle Plugin < 4.1.0, where namespace support is experimental anyway.
     */
    @Nullable
    File getResStaticLibrary();

    /**
     * Returns the location of the assets folder.
     *
     * @return a File for the assets folder. The file may not point to an existing folder.
     */
    @NotNull
    File getAssetsFolder();

}