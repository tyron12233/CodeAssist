package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.ExternalModuleDependency;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.MutableVersionConstraint;

public class DefaultExternalModuleDependency extends AbstractExternalModuleDependency implements ExternalModuleDependency {

    public DefaultExternalModuleDependency(String group, String name, String version) {
        this(group, name, version, null);
    }

    public DefaultExternalModuleDependency(String group, String name, String version, String configuration) {
        super(assertModuleId(group, name), version, configuration);
    }

    public DefaultExternalModuleDependency(ModuleIdentifier id, MutableVersionConstraint versionConstraint) {
        super(id, versionConstraint);
    }

    @Override
    public DefaultExternalModuleDependency copy() {
        DefaultExternalModuleDependency copiedModuleDependency = new DefaultExternalModuleDependency(getGroup(), getName(), getVersion(), getTargetConfiguration());
        copyTo(copiedModuleDependency);
        return copiedModuleDependency;
    }

    @Override
    public boolean contentEquals(Dependency dependency) {
        if (this == dependency) {
            return true;
        }
        if (dependency == null || getClass() != dependency.getClass()) {
            return false;
        }

        ExternalModuleDependency that = (ExternalModuleDependency) dependency;
        return isContentEqualsFor(that);

    }

    @Override
    public String toString() {
        return String.format("DefaultExternalModuleDependency{group='%s', name='%s', version='%s', configuration='%s'}",
                getGroup(), getName(), getVersion(), getTargetConfiguration() != null ? getTargetConfiguration() : Dependency.DEFAULT_CONFIGURATION);
    }
}
