package com.tyron.builder.project.api;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.List;
import java.util.Map;

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

    /**
     * Return a map of fully qualified name and the file object of an R.java class
     */
    Map<String, File> getResourceClasses();

    void addResourceClass(@NonNull File file);
}
