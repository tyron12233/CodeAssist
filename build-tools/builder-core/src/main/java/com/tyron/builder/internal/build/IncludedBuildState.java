package com.tyron.builder.internal.build;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.artifacts.DependencySubstitutions;
import com.tyron.builder.api.internal.GradleInternal;

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