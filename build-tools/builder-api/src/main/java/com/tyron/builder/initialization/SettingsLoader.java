package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;

public interface SettingsLoader {
    SettingsInternal findAndLoadSettings(GradleInternal gradle);
}
