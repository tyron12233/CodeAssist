package com.tyron.builder;


import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.invocation.Gradle;

import org.jetbrains.annotations.Nullable;

/**
 * <p>A {@code BuildResult} packages up the result of a build.</p>
 */
public class BuildResult {
    private final String action;
    private final Throwable failure;
    private final Gradle gradle;

    public BuildResult(@Nullable Gradle gradle, @Nullable Throwable failure) {
        this("Build", gradle, failure);
    }

    public BuildResult(String action, @Nullable Gradle gradle, @Nullable Throwable failure) {
        this.action = action;
        this.gradle = gradle;
        this.failure = failure;
    }

    @Nullable
    public Gradle getGradle() {
        return gradle;
    }

    @Nullable
    public Throwable getFailure() {
        return failure;
    }

    /**
     * The action performed by this build. Either `Build` or `Configure`.
     */
    public String getAction() {
        return action;
    }

    /**
     * <p>Rethrows the build failure. Does nothing if there was no build failure.</p>
     */
    public BuildResult rethrowFailure() {
        if (failure instanceof BuildException) {
            throw (BuildException) failure;
        }
        if (failure != null) {
            throw new BuildException(action + " aborted because of an internal error.", failure);
        }
        return this;
    }
}