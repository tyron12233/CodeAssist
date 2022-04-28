package com.tyron.builder.api.initialization;


import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencySubstitutions;

/**
 * A build that is to be included in the composite.
 *
 * @since 3.1
 */
public interface ConfigurableIncludedBuild extends IncludedBuild {

    /**
     * Sets the name of the included build.
     *
     * @param name the name of the build
     * @since 6.0
     */
    void setName(String name);

    /**
     * Configures the dependency substitution rules for this included build.
     *
     * The action receives an instance of {@link DependencySubstitutions} which can be configured with substitution rules.
     * Project dependencies are resolved in the context of the included build.
     *
     * @see org.gradle.api.artifacts.ResolutionStrategy#dependencySubstitution(Action)
     * @see DependencySubstitutions
     */
    void dependencySubstitution(Action<? super DependencySubstitutions> action);
}
