package org.gradle.api.artifacts;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.attributes.HasConfigurableAttributes;
import org.gradle.internal.HasInternalProtocol;

/**
 * Represents some variant of an outgoing configuration.
 *
 * @since 3.3
 */
@HasInternalProtocol
public interface ConfigurationVariant extends Named, HasConfigurableAttributes<ConfigurationVariant> {

    /**
     * Returns the artifacts associated with this variant.
     */
    PublishArtifactSet getArtifacts();

    /**
     * Adds an artifact to this variant.
     *
     * <p>See {@link org.gradle.api.artifacts.dsl.ArtifactHandler} for details of the supported notations.
     */
    void artifact(Object notation);

    /**
     * Adds an artifact to this variant, configuring it using the given action.
     *
     * <p>See {@link org.gradle.api.artifacts.dsl.ArtifactHandler} for details of the supported notations.
     */
    void artifact(Object notation, Action<? super ConfigurablePublishArtifact> configureAction);
}
