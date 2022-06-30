package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler;
import com.tyron.builder.api.artifacts.dsl.RepositoryHandler;
import com.tyron.builder.api.attributes.AttributesSchema;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.model.ObjectFactory;

public interface DependencyResolutionServices {
    RepositoryHandler getResolveRepositoryHandler();

    ConfigurationContainer getConfigurationContainer();

    DependencyHandler getDependencyHandler();

    DependencyLockingHandler getDependencyLockingHandler();

    ImmutableAttributesFactory getAttributesFactory();

    AttributesSchema getAttributesSchema();

    ObjectFactory getObjectFactory();
}
