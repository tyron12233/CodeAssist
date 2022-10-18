package org.gradle.api.component;

import org.gradle.api.Action;
import org.gradle.api.artifacts.Configuration;

/**
 * A component which can declare additional variants corresponding to
 * features. When published to Maven POMs, the dependencies of those variants
 * are exposed as optional dependencies. When published to Gradle metadata, the
 * variants are published as is.
 *
 * @since 5.3
 */
public interface AdhocComponentWithVariants extends SoftwareComponent {

    /**
     * Declares an additional variant to publish, corresponding to an additional feature.
     *
     * @param outgoingConfiguration the configuration corresponding to the variant to use as source of dependencies and artifacts
     * @param action the action to execute in order to determine if a configuration variant should be published or not
     */
    void addVariantsFromConfiguration(Configuration outgoingConfiguration, Action<? super ConfigurationVariantDetails> action);

    /**
     * Further configure previously declared variants.
     *
     * @param outgoingConfiguration the configuration corresponding to the variant to use as source of dependencies and artifacts
     * @param action the action to execute in order to determine if a configuration variant should be published or not
     *
     * @since 6.0
     */
    void withVariantsFromConfiguration(Configuration outgoingConfiguration, Action<? super ConfigurationVariantDetails> action);

}
