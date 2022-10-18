package org.gradle.api.internal.tasks.execution;

import org.gradle.api.Describable;
import org.gradle.api.GradleException;
import org.gradle.api.specs.Spec;

public class SelfDescribingSpec<T> implements Describable, Spec<T> {
    private final String description;
    private final Spec<? super T> spec;

    public SelfDescribingSpec(Spec<? super T> spec, String description) {
        this.spec = spec;
        this.description = description;
    }

    @Override
    public String getDisplayName() {
        return description;
    }

    @Override
    public boolean isSatisfiedBy(T element) {
        try {
            return spec.isSatisfiedBy(element);
        } catch (RuntimeException e) {
            throw new GradleException("Could not evaluate spec for '" + getDisplayName() + "'.", e);
        }
    }

    @Override
    public String toString() {
        return "SelfDescribingSpec{"
            + "description='" + description + '\''
            + '}';
    }
}
