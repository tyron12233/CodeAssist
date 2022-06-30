package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * Details about an artifact set being resolved.
 *
 * @since 4.0
 */
public final class ResolveArtifactsBuildOperationType implements BuildOperationType<ResolveArtifactsBuildOperationType.Details, ResolveArtifactsBuildOperationType.Result> {

//    @UsedByScanPlugin
    public interface Details {

        String getConfigurationPath();

    }

    public interface Result {

    }

    private ResolveArtifactsBuildOperationType() {
    }

}
