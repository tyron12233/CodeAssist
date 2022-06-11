package org.gradle.internal.execution.history.changes;

import org.gradle.api.GradleException;
import org.gradle.api.Describable;

public class ErrorHandlingChangeContainer implements ChangeContainer {
    private final Describable executable;
    private final ChangeContainer delegate;

    public ErrorHandlingChangeContainer(Describable executable, ChangeContainer delegate) {
        this.executable = executable;
        this.delegate = delegate;
    }

    @Override
    public boolean accept(ChangeVisitor visitor) {
        try {
            return delegate.accept(visitor);
        } catch (Exception ex) {
            throw new GradleException(String.format("Cannot determine changes for %s", executable.getDisplayName()), ex);
        }
    }
}