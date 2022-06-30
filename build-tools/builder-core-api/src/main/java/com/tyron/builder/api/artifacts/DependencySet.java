package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Buildable;
import com.tyron.builder.api.DomainObjectSet;

/**
 * A set of artifact dependencies.
 */
public interface DependencySet extends DomainObjectSet<Dependency>, Buildable {
}
