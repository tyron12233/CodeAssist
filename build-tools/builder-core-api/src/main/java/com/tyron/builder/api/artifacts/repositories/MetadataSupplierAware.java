package com.tyron.builder.api.artifacts.repositories;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.api.artifacts.ComponentMetadataSupplier;
import com.tyron.builder.api.artifacts.ComponentMetadataVersionLister;

/**
 * Interface for repositories which support custom metadata suppliers and/or version listers.
 * A custom version lister or metadata supplier can be used as an optimization technique to
 * avoid too many requests on a server. By providing such rules, a plugin or build author can
 * provide the necessary information to perform component selection without having to actually
 * fetch the component metadata on a server.
 *
 * @since 4.9
 */
public interface MetadataSupplierAware {
    /**
     * Sets a custom metadata rule, which is capable of supplying the metadata of a component (status, status scheme, changing flag)
     * whenever a dynamic version is requested. It can be used to provide metadata directly, instead of having to parse the Ivy
     * descriptor.
     *
     * @param rule the class of the rule. Gradle will instantiate a new rule for each dependency which requires metadata.
     *
     * @since 4.9
     */
    void setMetadataSupplier(Class<? extends ComponentMetadataSupplier> rule);

    /**
     * Sets a custom metadata rule, possibly configuring the rule.
     *
     * @param rule the class of the rule. Gradle will instantiate a new rule for each dependency which requires metadata.
     * @param configureAction the action to use to configure the rule.
     *
     * @since 4.9
     */
    void setMetadataSupplier(Class<? extends ComponentMetadataSupplier> rule, Action<? super ActionConfiguration> configureAction);

    /**
     * Sets a custom component versions lister. A versions lister will be called whenever a dynamic version is requested.
     *
     * @param lister the class of the lister.
     *
     * @since 4.9
     */
    void setComponentVersionsLister(Class<? extends ComponentMetadataVersionLister> lister);

    /**
     * Sets a custom component versions lister. A versions lister will be called whenever a dynamic version is requested.
     *
     * @param lister the class of the lister.
     * @param configureAction the action to use to configure the lister.
     *
     * @since 4.9
     */
    void setComponentVersionsLister(Class<? extends ComponentMetadataVersionLister> lister, Action<? super ActionConfiguration> configureAction);

}
