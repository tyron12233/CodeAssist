package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.attributes.HasConfigurableAttributes;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.provider.Provider;

import java.util.Collection;

/**
 * Represents the outgoing artifacts associated with a configuration. These artifacts are used when the configuration is referenced during dependency resolution.
 *
 * <p>You can use this interface associate artifacts with a configuration using the {@link #artifact(Object)} methods. You can also define several <em>variants</em> of the configuration's artifacts. Each variant represents a set of artifacts that form some mutually exclusive usage of the component.</p>
 *
 * <p>An implicit variant is defined for a configuration whenever any artifacts are attached directly to this object or inherited from another configuration.</p>
 *
 * @since 3.3
 */
public interface ConfigurationPublications extends HasConfigurableAttributes<ConfigurationPublications> {
    /**
     * Returns the artifacts associated with this configuration. When an artifact is added to this set, an implicit variant is defined for the configuration. These artifacts are also inherited by all configurations that extend this configuration.
     */
    PublishArtifactSet getArtifacts();

    /**
     * Adds an outgoing artifact to this configuration. This artifact is included in all variants.
     *
     * <p>See {@link com.tyron.builder.api.artifacts.dsl.ArtifactHandler} for details of the supported notations.
     */
    void artifact(Object notation);

    /**
     * Adds an outgoing artifact to this configuration, configuring it using the given action. This artifact is included in all variants.
     *
     * <p>See {@link com.tyron.builder.api.artifacts.dsl.ArtifactHandler} for details of the supported notations.
     */
    void artifact(Object notation, Action<? super ConfigurablePublishArtifact> configureAction);

    /**
     * Lazily adds a collection of outgoing artifacts to this configuration. These artifacts are included in all variants.
     *
     * @param provider The provider of the artifacts to add.
     * @since 7.4
     */
    @Incubating
    void artifacts(Provider<? extends Iterable<? extends Object>> provider);

    /**
     * Lazily adds a collection of outgoing artifacts to this configuration, configuring each artifact using the given action. These artifacts are included in all variants.
     *
     * @param provider The provider of the artifacts to add.
     * @since 7.4
     */
    @Incubating
    void artifacts(Provider<? extends Iterable<? extends Object>> provider, Action<? super ConfigurablePublishArtifact> configureAction);

    /**
     * Returns the variants of this configuration, if any.
     */
    NamedDomainObjectContainer<ConfigurationVariant> getVariants();

    /**
     * Configures the variants of this configuration.
     */
    void variants(Action<? super NamedDomainObjectContainer<ConfigurationVariant>> configureAction);

    /**
     * Declares a capability for this configuration.
     *
     * @param notation the notation
     *
     * Valid notations are a <i>group:name:version</i> string (e.g: <i>org.test:capability:1.0</i>, or a map
     * with keys <i>group</i>, <i>name</i> and <i>version</i>.
     *
     * @since 4.7.
     */
    void capability(Object notation);

    /**
     * Returns the capabilities declared for this configuration.
     *
     * @return the capabilities for this variant
     *
     * @since 4.7
     */
    Collection<? extends Capability> getCapabilities();
}
