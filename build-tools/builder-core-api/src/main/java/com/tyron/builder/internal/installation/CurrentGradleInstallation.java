package com.tyron.builder.internal.installation;

import javax.annotation.Nullable;

/**
 * Provides access to the current Gradle installation associated with the runtime.
 */
public class CurrentGradleInstallation {

    private static CurrentGradleInstallation instance;

    private final GradleInstallation gradleInstallation;

    public CurrentGradleInstallation(@Nullable GradleInstallation gradleInstallation) {
        this.gradleInstallation = gradleInstallation;
    }

    @Nullable // if no installation can be located
    public GradleInstallation getInstallation() {
        return gradleInstallation;
    }

    @Nullable // if no installation can be located
    public static GradleInstallation get() {
        return locate().getInstallation();
    }

    public synchronized static CurrentGradleInstallation locate() {
        if (instance == null) {
            instance = CurrentGradleInstallationLocator.locate();
        }
        return instance;
    }

}
