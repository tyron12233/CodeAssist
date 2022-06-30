package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.Describable;

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
            throw new BuildException(String.format("Cannot determine changes for %s", executable.getDisplayName()), ex);
        }
    }
}