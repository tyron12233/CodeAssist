package com.tyron.builder.api.artifacts.dsl;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencyConstraint;

/**
 * <p>A {@code DependencyConstraintHandler} is used to declare dependency constraints.</p>
 *
 * @since 4.5
 */
public interface DependencyConstraintHandler {
    /**
     * Adds a dependency constraint to the given configuration.
     *
     * @param configurationName The name of the configuration.
     * @param dependencyConstraintNotation the constraint
     */
    DependencyConstraint add(String configurationName, Object dependencyConstraintNotation);

    /**
     * Adds a dependency constraint to the given configuration, and configures the dependency constraint using the given closure.
     *
     * @param configurationName The name of the configuration.
     * @param dependencyNotation The dependency constraint notation
     * @param configureAction The closure to use to configure the dependency constraint.
     */
    DependencyConstraint add(String configurationName, Object dependencyNotation, Action<? super DependencyConstraint> configureAction);

    /**
     * Creates a dependency constraint without adding it to a configuration.
     *
     * @param dependencyConstraintNotation The dependency constraint notation.
     */
    DependencyConstraint create(Object dependencyConstraintNotation);

    /**
     * Creates a dependency constraint without adding it to a configuration, and configures the dependency constraint using
     * the given closure.
     *
     * @param dependencyConstraintNotation The dependency constraint notation.
     * @param configureAction The closure to use to configure the dependency.
     */
    DependencyConstraint create(Object dependencyConstraintNotation, Action<? super DependencyConstraint> configureAction);

    /**
     * Declares a constraint on an enforced platform. If the target coordinates represent multiple
     * potential components, the platform component will be selected, instead of the library.
     * An enforced platform is a platform for which the direct dependencies are forced, meaning
     * that they would override any other version found in the graph.
     *
     * @param notation the coordinates of the platform
     *
     * @since 5.0
     */
    DependencyConstraint enforcedPlatform(Object notation);

    /**
     * Declares a constraint on an enforced platform. If the target coordinates represent multiple
     * potential components, the platform component will be selected, instead of the library.
     * An enforced platform is a platform for which the direct dependencies are forced, meaning
     * that they would override any other version found in the graph.
     *
     * @param notation the coordinates of the platform
     * @param configureAction the dependency configuration block
     *
     * @since 5.0
     */
    DependencyConstraint enforcedPlatform(Object notation, Action<? super DependencyConstraint> configureAction);
}
