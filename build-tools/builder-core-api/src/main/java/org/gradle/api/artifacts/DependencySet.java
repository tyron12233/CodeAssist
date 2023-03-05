package org.gradle.api.artifacts;

import org.gradle.api.Buildable;
import org.gradle.api.DomainObjectSet;

/**
 * A set of artifact dependencies.
 */
public interface DependencySet extends DomainObjectSet<Dependency>, Buildable {
}
