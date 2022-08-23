package com.tyron.builder.model;

import com.tyron.builder.model.v2.CustomSourceDirectory;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collection;

/**
 * Represent a SourceProvider for a given configuration.
 *
 * TODO: source filters?
 */
public interface SourceProvider {

    /**
     * Returns the name of this source set.
     *
     * @return The name. Never returns null.
     */
    @NotNull
    String getName();

    /**
     * Returns the manifest file.
     *
     * @return the manifest file. It may not exist.
     */
    @NotNull
    File getManifestFile();

    /**
     * Returns the java source folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getJavaDirectories();

    /**
     * Returns the kotlin source folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getKotlinDirectories();

    /**
     * Returns the java resources folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getResourcesDirectories();

    /**
     * Returns the aidl source folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getAidlDirectories();

    /**
     * Returns the renderscript source folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getRenderscriptDirectories();

    /**
     * Returns the C source folders.
     *
     * @return a list of folders. They may not all exist.
     * @deprecated since ndk-compile is deprecated
     */
    @NotNull
    @Deprecated
    Collection<File> getCDirectories();

    /**
     * Returns the C++ source folders.
     *
     * @return a list of folders. They may not all exist.
     * @deprecated since ndk-compile is deprecated
     */
    @NotNull
    @Deprecated
    Collection<File> getCppDirectories();

    /**
     * Returns the android resources folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getResDirectories();

    /**
     * Returns the android assets folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getAssetsDirectories();

    /**
     * Returns the native libs folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getJniLibsDirectories();

    /**
     * Returns the shader folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getShadersDirectories();

    /**
     * Returns the machine learning models folders.
     *
     * @return a list of folders. They may not all exist.
     */
    @NotNull
    Collection<File> getMlModelsDirectories();

    /**
     * Returns the map of registered custom directories, key is the name provided when registering
     * the source set, and value is the list of folders for that custom source set.
     *
     * @return a map of source set name to source set folders.
     */
    @NotNull
    Collection<CustomSourceDirectory> getCustomDirectories();
}