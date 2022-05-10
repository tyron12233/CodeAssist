package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.DependencyConstraint;

public interface DependencyConstraintInternal extends DependencyConstraint {
    void setForce(boolean force);
    boolean isForce();
    DependencyConstraint copy();
}
