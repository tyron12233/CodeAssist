/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.api.internal.artifacts.configurations;

import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.RootComponentMetadataBuilder;

import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.artifacts.DependencyResolutionListener;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.artifacts.ConfigurationResolver;
import com.tyron.builder.api.internal.artifacts.dsl.CapabilityNotationParserFactory;
import com.tyron.builder.api.internal.artifacts.dsl.PublishArtifactNotationParserFactory;

import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.event.ListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.work.WorkerThreadRegistry;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

/**
 * Factory for creating {@link com.tyron.builder.api.artifacts.Configuration} instances.
 */
@ThreadSafe
public class DefaultConfigurationFactory {

    private final Instantiator instantiator;
    private final ConfigurationResolver resolver;
    private final ListenerManager listenerManager;
    private final DependencyMetaDataProvider metaDataProvider;
    private final DomainObjectContext domainObjectContext;
    private final FileCollectionFactory fileCollectionFactory;
    private final BuildOperationExecutor buildOperationExecutor;
    private final NotationParser<Object, ConfigurablePublishArtifact> artifactNotationParser;
    private final NotationParser<Object, Capability> capabilityNotationParser;
    private final ImmutableAttributesFactory attributesFactory;
    private final DocumentationRegistry documentationRegistry;
    private final UserCodeApplicationContext userCodeApplicationContext;
    private final ProjectStateRegistry projectStateRegistry;
    private final WorkerThreadRegistry workerThreadRegistry;
    private final DomainObjectCollectionFactory domainObjectCollectionFactory;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;

    @Inject
    public DefaultConfigurationFactory(
        Instantiator instantiator,
        ConfigurationResolver resolver,
        ListenerManager listenerManager,
        DependencyMetaDataProvider metaDataProvider,
        DomainObjectContext domainObjectContext,
        FileCollectionFactory fileCollectionFactory,
        BuildOperationExecutor buildOperationExecutor,
        PublishArtifactNotationParserFactory artifactNotationParserFactory,
        ImmutableAttributesFactory attributesFactory,
        DocumentationRegistry documentationRegistry,
        UserCodeApplicationContext userCodeApplicationContext,
        ProjectStateRegistry projectStateRegistry,
        WorkerThreadRegistry workerThreadRegistry,
        DomainObjectCollectionFactory domainObjectCollectionFactory,
        CalculatedValueContainerFactory calculatedValueContainerFactory
    ) {
        this.instantiator = instantiator;
        this.resolver = resolver;
        this.listenerManager = listenerManager;
        this.metaDataProvider = metaDataProvider;
        this.domainObjectContext = domainObjectContext;
        this.fileCollectionFactory = fileCollectionFactory;
        this.buildOperationExecutor = buildOperationExecutor;
        this.artifactNotationParser = artifactNotationParserFactory.create();
        this.capabilityNotationParser = new CapabilityNotationParserFactory(true).create();
        this.attributesFactory = attributesFactory;
        this.documentationRegistry = documentationRegistry;
        this.userCodeApplicationContext = userCodeApplicationContext;
        this.projectStateRegistry = projectStateRegistry;
        this.workerThreadRegistry = workerThreadRegistry;
        this.domainObjectCollectionFactory = domainObjectCollectionFactory;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
    }

    /**
     * Creates a new {@link DefaultConfiguration} instance.
     */
    DefaultConfiguration create(
        String name,
        ConfigurationsProvider configurationsProvider,
        Factory<ResolutionStrategyInternal> resolutionStrategyFactory,
        RootComponentMetadataBuilder rootComponentMetadataBuilder
    ) {
        ListenerBroadcast<DependencyResolutionListener> dependencyResolutionListeners =
            listenerManager.createAnonymousBroadcaster(DependencyResolutionListener.class);
        return instantiator.newInstance(
            DefaultConfiguration.class,
            domainObjectContext,
            name,
            configurationsProvider,
            resolver,
            dependencyResolutionListeners,
            listenerManager.getBroadcaster(ProjectDependencyObservedListener.class),
            metaDataProvider,
            resolutionStrategyFactory,
            fileCollectionFactory,
            buildOperationExecutor,
            instantiator,
            artifactNotationParser,
            capabilityNotationParser,
            attributesFactory,
            rootComponentMetadataBuilder,
            documentationRegistry,
            userCodeApplicationContext,
            projectStateRegistry,
            workerThreadRegistry,
            domainObjectCollectionFactory,
            calculatedValueContainerFactory,
            this
        );
    }
}
