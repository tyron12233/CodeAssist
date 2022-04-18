package com.tyron.builder.initialization;

import javax.annotation.Nullable;
import java.io.File;

public class SettingsLocation {
    private final File settingsDir;

    @Nullable
    private final File settingsFile;

    public SettingsLocation(File settingsDir, @Nullable File settingsFile) {
        this.settingsDir = settingsDir;
        this.settingsFile = settingsFile;
    }

    /**
     * Returns the settings directory. Never null.
     */
    public File getSettingsDir() {
        return settingsDir;
    }

    /**
     * Returns the settings file. May be null, which mean "no settings file" rather than "use default settings".
     */
    @Nullable
    public File getSettingsFile() {
        return settingsFile;
    }
}