package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.specs.Spec;

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
            throw new BuildException("Could not evaluate spec for '" + getDisplayName() + "'.", e);
        }
    }

    @Override
    public String toString() {
        return "SelfDescribingSpec{"
            + "description='" + description + '\''
            + '}';
    }
}
