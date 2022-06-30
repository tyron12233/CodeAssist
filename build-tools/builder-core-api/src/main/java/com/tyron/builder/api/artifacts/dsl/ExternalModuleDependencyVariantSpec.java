package com.tyron.builder.api.artifacts.dsl;

import com.tyron.builder.api.Incubating;

/**
 * The specification of a dependency variant. Some dependencies can be fined tuned
 * to select a particular variant. For example, one might want to select the test
 * fixtures of a target component, or a specific classifier.
 *
 * @since 6.8
 */
@Incubating
public interface ExternalModuleDependencyVariantSpec {
    /**
     * Configures the dependency to select the "platform" variant.
     */
    void platform();

    /**
     * Configures the dependency to select the test fixtures capability.
     */
    void testFixtures();

    /**
     * Configures the classifier of this dependency
     */
    void classifier(String classifier);

    /**
     * Configures the artifact type of this dependency
     */
    void artifactType(String artifactType);

}
