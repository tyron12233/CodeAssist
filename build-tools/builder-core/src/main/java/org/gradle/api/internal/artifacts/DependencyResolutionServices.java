package org.gradle.api.internal.artifacts;

import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.DependencyLockingHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.model.ObjectFactory;

public interface DependencyResolutionServices {
    RepositoryHandler getResolveRepositoryHandler();

    ConfigurationContainer getConfigurationContainer();

    DependencyHandler getDependencyHandler();

    DependencyLockingHandler getDependencyLockingHandler();

    ImmutableAttributesFactory getAttributesFactory();

    AttributesSchema getAttributesSchema();

    ObjectFactory getObjectFactory();
}
