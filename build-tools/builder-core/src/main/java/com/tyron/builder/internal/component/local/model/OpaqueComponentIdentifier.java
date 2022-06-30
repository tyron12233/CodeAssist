package com.tyron.builder.internal.component.local.model;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyFactory;

public class OpaqueComponentIdentifier implements ComponentIdentifier {
    private final DependencyFactory.ClassPathNotation classPathNotation;

    public OpaqueComponentIdentifier(DependencyFactory.ClassPathNotation classPathNotation) {
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

    public DependencyFactory.ClassPathNotation getClassPathNotation() {
        return classPathNotation;
    }
}
