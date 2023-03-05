package org.gradle.api.artifacts.component;

import org.gradle.internal.scan.UsedByScanPlugin;

/**
 * Criteria for selecting a component instance that is built as part of the current build.
 *
 * @since 1.10
 */
@UsedByScanPlugin
public interface ProjectComponentSelector extends ComponentSelector {
    /**
     * The name of the build to select a project from.
     *
     * @return The build name
     */
    String getBuildName();

    /**
     * The path of the project to select the component from.
     *
     * @return Project path
     * @since 1.10
     */
    String getProjectPath();
}
