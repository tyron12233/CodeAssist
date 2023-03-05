package org.gradle.internal.composite;

import org.gradle.api.initialization.IncludedBuild;
import org.gradle.internal.build.BuildState;

public interface IncludedBuildInternal extends IncludedBuild {
    /**
     * Returns the build which this include refers to.
     */
    BuildState getTarget();
}