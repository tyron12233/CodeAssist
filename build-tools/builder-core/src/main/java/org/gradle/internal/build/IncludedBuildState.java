package org.gradle.internal.build;

import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.DependencySubstitutions;
import org.gradle.api.internal.GradleInternal;

import java.io.File;

/**
 * Encapsulates the identity and state of an included build. An included build is a nested build that participates in dependency resolution and task execution with the root build and other included builds in the build tree.
 */
public interface IncludedBuildState extends NestedBuildState, CompositeBuildParticipantBuildState {
    String getName();

    File getRootDirectory();

    boolean isPluginBuild();

    Action<? super DependencySubstitutions> getRegisteredDependencySubstitutions();

    <T> T withState(Transformer<T, ? super GradleInternal> action);
}