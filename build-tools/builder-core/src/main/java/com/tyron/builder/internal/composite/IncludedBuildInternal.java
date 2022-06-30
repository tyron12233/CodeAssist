package com.tyron.builder.internal.composite;

import com.tyron.builder.api.initialization.IncludedBuild;
import com.tyron.builder.internal.build.BuildState;

public interface IncludedBuildInternal extends IncludedBuild {
    /**
     * Returns the build which this include refers to.
     */
    BuildState getTarget();
}