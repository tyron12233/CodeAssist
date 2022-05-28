package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;

public class DefaultModule implements Module {
    private final String group;
    private final String name;
    private final String version;
    private String status = DEFAULT_STATUS;

    public DefaultModule(String group, String name, String version) {
        this.group = group;
        this.name = name;
        this.version = version;
    }

    public DefaultModule(String group, String name, String version, String status) {
        this.group = group;
        this.name = name;
        this.version = version;
        this.status = status;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public ProjectComponentIdentifier getProjectId() {
        return null;
    }
}
