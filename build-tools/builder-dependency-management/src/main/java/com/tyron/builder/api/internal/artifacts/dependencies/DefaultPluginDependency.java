package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.MutableVersionConstraint;
import com.tyron.builder.api.artifacts.VersionConstraint;
import com.tyron.builder.plugin.use.PluginDependency;

public class DefaultPluginDependency implements PluginDependency {
    private final String pluginId;
    private final MutableVersionConstraint versionConstraint;

    public DefaultPluginDependency(String pluginId, MutableVersionConstraint versionConstraint) {
        this.pluginId = pluginId;
        this.versionConstraint = versionConstraint;
    }

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public VersionConstraint getVersion() {
        return versionConstraint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultPluginDependency that = (DefaultPluginDependency) o;

        if (!pluginId.equals(that.pluginId)) {
            return false;
        }
        return versionConstraint.equals(that.versionConstraint);
    }

    @Override
    public int hashCode() {
        int result = pluginId.hashCode();
        result = 31 * result + versionConstraint.hashCode();
        return result;
    }

    @Override
    public String toString() {
        String versionConstraintAsString = versionConstraint.toString();
        return versionConstraintAsString.isEmpty()
            ? pluginId
            : pluginId + ":" + versionConstraintAsString;
    }
}
