package com.tyron.builder.initialization;

import java.io.File;

public interface GradleUserHomeDirProvider {
    /**
     * Returns the user home directory for the current build.
     */
    File getGradleUserHomeDirectory();
}