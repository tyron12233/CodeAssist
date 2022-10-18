package com.tyron.builder.plugin.builder;

import java.io.File;
import java.util.List;
/**
 * Represents a dependency on a CodeAssistLibrary Project.
 */
public interface AndroidDependency {
    /**
     * Returns the location of the unarchived bundle.
     */
    File getFolder();
    /**
     * Returns the direct dependency of this dependency.
     */
    List<AndroidDependency> getDependencies();
    /**
     * Returns the location of the jar file to use for packaging.
     * Cannot be null.
     */
    File getJarFile();
    /**
     * Returns the location of the manifest.
     */
    File getManifest();
    /**
     * Returns the location of the res folder.
     */
    File getResFolder();
    /**
     * Returns the location of the assets folder.
     */
    File getAssetsFolder();
    /**
     * Returns the location of the jni libraries folder.
     */
    File getJniFolder();
    /**
     * Returns the location of the aidl import folder.
     */
    File getAidlFolder();
    /**
     * Returns the location of the proguard files.
     */
    File getProguardRules();
    /**
     * Returns the location of the lint jar.
     */
    File getLintJar();
}