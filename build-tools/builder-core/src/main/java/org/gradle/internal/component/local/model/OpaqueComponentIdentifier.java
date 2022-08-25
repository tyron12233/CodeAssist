package org.gradle.internal.component.local.model;

import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactoryInternal;

public class OpaqueComponentIdentifier implements ComponentIdentifier {
    private final DependencyFactoryInternal.ClassPathNotation classPathNotation;

    public OpaqueComponentIdentifier(DependencyFactoryInternal.ClassPathNotation classPathNotation) {
        assert classPathNotation != null;
        this.classPathNotation = classPathNotation;
    }

    @Override
    public String getDisplayName() {
        return classPathNotation.displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        OpaqueComponentIdentifier that = (OpaqueComponentIdentifier) o;

        return classPathNotation.equals(that.classPathNotation);
    }

    @Override
    public int hashCode() {
        return classPathNotation.hashCode();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    public DependencyFactoryInternal.ClassPathNotation getClassPathNotation() {
        return classPathNotation;
    }
}
