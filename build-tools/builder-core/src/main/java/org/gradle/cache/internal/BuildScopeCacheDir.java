package org.gradle.cache.internal;

import org.gradle.StartParameter;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.initialization.layout.BuildLayout;

import java.io.File;

public class BuildScopeCacheDir {
    public static final String UNDEFINED_BUILD = "undefined-build";

    private final File cacheDir;

    public BuildScopeCacheDir(
            GradleUserHomeDirProvider userHomeDirProvider,
            BuildLayout buildLayout,
            StartParameter startParameter
    ) {
        if (startParameter.getProjectCacheDir() != null) {
            cacheDir = startParameter.getProjectCacheDir();
        } else if (!buildLayout.getRootDirectory().getName().equals(SettingsInternal.BUILD_SRC) && buildLayout.isBuildDefinitionMissing()) {
            cacheDir = new File(userHomeDirProvider.getGradleUserHomeDirectory(), UNDEFINED_BUILD);
        } else {
            cacheDir = new File(buildLayout.getRootDirectory(), ".gradle");
        }
        if (cacheDir.exists() && !cacheDir.isDirectory()) {
            throw new UncheckedIOException(String.format("Cache directory '%s' exists and is not a directory.", cacheDir));
        }
    }

    public File getDir() {
        return cacheDir;
    }
}

