package com.tyron.builder.model.level2;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A node in a dependency graph, representing a direct or transitive dependency.
 *
 * This does not directly contain artifact information, instead it focuses on the graph
 * information (transitive dependencies) as well as the usage of this particular dependency
 * in this node of the graph (ie what are its modifiers: what version was originally requested.)
 *
 * @since 2.3
 */
public interface GraphItem {

    /**
     * Returns the artifact address in a unique way.
     *
     * This is either a module path for sub-modules, or a maven coordinate for external
     * dependencies.
     *
     * The maven coordinates are in the format: groupId:artifactId:version[:classifier][@extension]
     *
     */
    @NotNull
    String getArtifactAddress();

    /**
     * Returns this library's Maven coordinates, as requested in the build file.
     */
    @Nullable
    String getRequestedCoordinates();

    /**
     * Return the direct dependency of this node.
     */
    @NotNull
    List<GraphItem> getDependencies();
}