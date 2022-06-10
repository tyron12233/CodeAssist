package org.gradle.internal.build;

import org.gradle.api.internal.StartParameterInternal;

/**
 * Represents the root build of a build tree.
 */
public interface RootBuildState extends CompositeBuildParticipantBuildState, BuildActionTarget {
    /**
     * Returns the start parameter used to define this build.
     */
    StartParameterInternal getStartParameter();
}