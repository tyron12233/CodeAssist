package org.gradle.api.internal.artifacts;

import com.google.common.base.Objects;
import org.gradle.api.artifacts.component.BuildIdentifier;

public class DefaultBuildIdentifier implements BuildIdentifier {
    public static final BuildIdentifier ROOT = new DefaultBuildIdentifier(":");
    private final String name;

    public DefaultBuildIdentifier(String name) {
        this.name = name;
    }

    public String getIdName() {
        return name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isCurrentBuild() {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultBuildIdentifier)) {
            return false;
        }
        DefaultBuildIdentifier that = (DefaultBuildIdentifier) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return "build '" + name + "'";
    }
}