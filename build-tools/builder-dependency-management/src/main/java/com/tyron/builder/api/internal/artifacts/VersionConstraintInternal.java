package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.MutableVersionConstraint;

public interface VersionConstraintInternal extends MutableVersionConstraint {
    ImmutableVersionConstraint asImmutable();
}
