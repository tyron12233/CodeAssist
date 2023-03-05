package org.gradle.initialization;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;

public interface SettingsLoader {
    SettingsInternal findAndLoadSettings(GradleInternal gradle);
}
