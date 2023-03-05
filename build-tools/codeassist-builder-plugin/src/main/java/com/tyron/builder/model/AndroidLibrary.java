package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collection;

/**
 * Represents an Android CodeAssistLibrary dependency, its content and its own dependencies.
 */
public interface AndroidLibrary extends AndroidBundle {

    /**
     * Returns the list of local Jar files that are included in the dependency.
     *
     * @return a list of File. May be empty but not null.
     */
    @NotNull
    Collection<File> getLocalJars();

    /**
     * Returns the location of the jni libraries folder.
     *
     * @return a File for the folder. The file may not point to an existing folder.
     */
    @NotNull
    File getJniFolder();

    /**
     * Returns the location of the aidl import folder.
     *
     * @return a File for the folder. The file may not point to an existing folder.
     */
    @NotNull
    File getAidlFolder();

    /**
     * Returns the location of the renderscript import folder.
     *
     * @return a File for the folder. The file may not point to an existing folder.
     */
    @NotNull
    File getRenderscriptFolder();

    /**
     * Returns the location of the proguard files.
     *
     * @return a File for the file. The file may not point to an existing file.
     */
    @NotNull
    File getProguardRules();

    /**
     * Returns the location of the lint jar.
     *
     * @return a File for the jar file. The file may not point to an existing file.
     */
    @NotNull
    File getLintJar();

    /**
     * Returns the location of the external annotations zip file (which may not exist)
     *
     * @return a File for the zip file. The file may not point to an existing file.
     */
    @NotNull
    File getExternalAnnotations();

    /**
     * Returns the location of an optional file that lists the only
     * resources that should be considered public.
     *
     * @return a File for the file. The file may not point to an existing file.
     */
    @NotNull
    File getPublicResources();

    /**
     * Returns the location of the text symbol file
     */
    @NotNull
    File getSymbolFile();

    /**
     * Returns whether the library is considered optional, meaning that it may or may not
     * be present in the final APK.
     *
     * If the library is optional, then:
     * - if the consumer is a library, it'll get skipped from resource merging and won't show up
     *   in the consumer R.txt
     * - if the consumer is a separate test project, all the resources gets skipped from merging.
     */
    @Override
    boolean isProvided();

    /**
     * @deprecated Use {@link #isProvided()} instead
     */
    @Deprecated
    boolean isOptional();

}