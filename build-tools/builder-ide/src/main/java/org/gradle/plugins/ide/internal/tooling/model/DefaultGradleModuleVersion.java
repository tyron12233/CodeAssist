package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.tooling.model.GradleModuleVersion;

import java.io.Serializable;

public class DefaultGradleModuleVersion implements GradleModuleVersion, Serializable {

    private final String group;
    private final String name;
    private final String version;

    public DefaultGradleModuleVersion(ModuleVersionIdentifier identifier) {
        this.group = identifier.getGroup();
        this.name = identifier.getName();
        this.version = identifier.getVersion();
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
    public String toString() {
        return "GradleModuleVersion{"
                 + "group='" + group + '\''
                 + ", name='" + name + '\''
                 + ", version='" + version + '\''
                 + '}';
    }
}