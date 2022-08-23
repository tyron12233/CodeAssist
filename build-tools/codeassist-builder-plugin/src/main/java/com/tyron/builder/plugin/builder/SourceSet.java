package com.tyron.builder.plugin.builder;

import java.io.File;
import java.util.Set;

/**
 * Represent an Android SourceSet for a given configuration.
 */
public interface SourceSet {
    Set<File> getJavaResources();

    Iterable<File> getCompileClasspath();

    File getAndroidResources();

    File getAndroidAssets();

    File getAndroidManifest();

    File getAidlSource();

    File getRenderscriptSource();

    File getNativeSource();
}