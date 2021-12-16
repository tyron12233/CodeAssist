package com.tyron.builder.model;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ProjectSettings extends ModuleSettings {

    public ProjectSettings(File configFile) {
        super(configFile);
    }

    @Override
    protected Map<String, Object> getDefaults() {
        return new HashMap<>();
    }
}
