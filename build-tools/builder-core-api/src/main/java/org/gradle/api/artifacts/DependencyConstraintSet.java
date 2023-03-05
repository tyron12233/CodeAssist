package org.gradle.api.artifacts;

import org.gradle.api.DomainObjectSet;

/**
 * A set of dependency constraint definitions that are associated with a configuration.
 *
 * @since 4.6
 */
public interface DependencyConstraintSet extends DomainObjectSet<DependencyConstraint> {
}
