package org.gradle.initialization;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.project.ProjectInternal;

public interface BuildLoader {
    /**
     * Creates prepares the {@link ProjectInternal} instances for the given settings,
     * ready for the projects to be configured.
     */
    void load(SettingsInternal settings, GradleInternal gradle);
}