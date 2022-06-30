package com.tyron.builder.api.artifacts.component;

/**
 * An identifier for a component instance that is built as part of the current build.
 *
 * @since 1.10
 */
//@UsedByScanPlugin
public interface ProjectComponentIdentifier extends ComponentIdentifier {
    /**
     * Identifies the build that contains the project that produces this component.
     *
     * @return The build identifier
     */
    BuildIdentifier getBuild();

    /**
     * Returns the path of the project that produces this component. This path is relative to the containing build, so for example will return ':' for the root project of a build.
     *
     * @since 1.10
     */
    String getProjectPath();

    /**
     * Returns the simple name of the project that produces this component.
     *
     * @since 4.5
     */
    String getProjectName();
}