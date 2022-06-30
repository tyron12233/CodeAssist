package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;

public interface BuildLoader {
    /**
     * Creates prepares the {@link ProjectInternal} instances for the given settings,
     * ready for the projects to be configured.
     */
    void load(SettingsInternal settings, GradleInternal gradle);
}