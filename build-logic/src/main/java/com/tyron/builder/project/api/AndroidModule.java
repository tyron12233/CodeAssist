package com.tyron.builder.project.api;

import java.io.File;

public interface AndroidModule extends JavaModule, KotlinModule {

    /**
     * @return The directory where android resource xml files are searched
     */
    File getAndroidResourcesDirectory();

    File getNativeLibrariesDirectory();

    File getAssetsDirectory();

    String getPackageName();

    File getManifestFile();

    int getTargetSdk();

    int getMinSdk();
}
