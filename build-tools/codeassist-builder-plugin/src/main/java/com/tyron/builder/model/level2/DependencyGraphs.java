package com.tyron.builder.model.level2;

import com.tyron.builder.model.AndroidProject;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A set of dependency Graphs.
 *
 * It contains both the compile and the package graphs, through the latter could be empty in
 * non full sync.
 *
 * Each graph is fairly lightweight, with each artifact node being mostly an address, children,
 * and modifiers that are specific to this particular usage of the artifact rather than
 * artifact properties.*
 *
 * @see AndroidProject#PROPERTY_BUILD_MODEL_FEATURE_FULL_DEPENDENCIES
 */
public interface DependencyGraphs {

    /**
     * Returns the compile dependency graph.
     */
    @NotNull
    List<GraphItem> getCompileDependencies();

    /**
     * Returns the package dependency graph.
     *
     * Only valid in full dependency mode.
     */
    @NotNull
    List<GraphItem> getPackageDependencies();

    /**
     * Returns the list of provided libraries.
     *
     * The values in the list match the values returned by {@link GraphItem#getArtifactAddress()}
     * and {@link Library#getArtifactAddress()}.
     *
     * Only valid in full dependency mode.
     */
    @NotNull
    List<String> getProvidedLibraries();

    /**
     * Returns the list of skipped libraries.
     *
     * The values in the list match the values returned by {@link GraphItem#getArtifactAddress()}
     * and {@link Library#getArtifactAddress()}.
     *
     * Only valid in full dependency mode.
     */
    @NotNull
    List<String> getSkippedLibraries();

}