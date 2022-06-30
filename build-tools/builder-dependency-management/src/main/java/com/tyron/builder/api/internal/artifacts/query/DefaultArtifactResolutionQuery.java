/*
 * Copyright 2014 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.query;

import com.google.common.collect.Sets;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ErrorHandlingArtifactResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory;
import com.tyron.builder.api.internal.artifacts.repositories.ResolutionAwareRepository;
import com.tyron.builder.api.internal.artifacts.result.DefaultResolvedArtifactResult;
import com.tyron.builder.api.internal.artifacts.result.DefaultUnresolvedArtifactResult;
import com.tyron.builder.api.internal.artifacts.result.DefaultUnresolvedComponentResult;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentIdentifier;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.artifacts.query.ArtifactResolutionQuery;
import com.tyron.builder.api.artifacts.result.ArtifactResolutionResult;
import com.tyron.builder.api.artifacts.result.ComponentArtifactsResult;
import com.tyron.builder.api.artifacts.result.ComponentResult;
import com.tyron.builder.api.component.Artifact;
import com.tyron.builder.api.component.Component;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.GlobalDependencyResolutionRules;
import com.tyron.builder.api.internal.artifacts.RepositoriesSupplier;
import com.tyron.builder.api.internal.artifacts.result.DefaultArtifactResolutionResult;
import com.tyron.builder.api.internal.artifacts.result.DefaultComponentArtifactsResult;

import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.api.internal.component.ComponentTypeRegistry;
import com.tyron.builder.internal.Describables;

import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.DefaultComponentOverrideMetadata;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataSupplierRuleExecutor;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;
import com.tyron.builder.internal.resolve.resolver.ComponentMetaDataResolver;
import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentResolveResult;
import com.tyron.builder.internal.resolve.result.DefaultBuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.DefaultBuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.DefaultBuildableComponentResolveResult;
import com.tyron.builder.util.internal.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class DefaultArtifactResolutionQuery implements ArtifactResolutionQuery {
    private final ConfigurationContainerInternal configurationContainer;
    private final RepositoriesSupplier repositoriesSupplier;
    private final ResolveIvyFactory ivyFactory;
    private final GlobalDependencyResolutionRules metadataHandler;
    private final ComponentTypeRegistry componentTypeRegistry;
    private final ImmutableAttributesFactory attributesFactory;
    private final ComponentMetadataSupplierRuleExecutor componentMetadataSupplierRuleExecutor;

    private final Set<ComponentIdentifier> componentIds = Sets.newLinkedHashSet();
    private Class<? extends Component> componentType;
    private final Set<Class<? extends Artifact>> artifactTypes = Sets.newLinkedHashSet();

    public DefaultArtifactResolutionQuery(ConfigurationContainerInternal configurationContainer,
                                          RepositoriesSupplier repositoriesSupplier,
                                          ResolveIvyFactory ivyFactory,
                                          GlobalDependencyResolutionRules metadataHandler,
                                          ComponentTypeRegistry componentTypeRegistry,
                                          ImmutableAttributesFactory attributesFactory,
                                          ComponentMetadataSupplierRuleExecutor componentMetadataSupplierRuleExecutor) {
        this.configurationContainer = configurationContainer;
        this.repositoriesSupplier = repositoriesSupplier;
        this.ivyFactory = ivyFactory;
        this.metadataHandler = metadataHandler;
        this.componentTypeRegistry = componentTypeRegistry;
        this.attributesFactory = attributesFactory;
        this.componentMetadataSupplierRuleExecutor = componentMetadataSupplierRuleExecutor;
    }

    @Override
    public ArtifactResolutionQuery forComponents(Iterable<? extends ComponentIdentifier> componentIds) {
        CollectionUtils.addAll(this.componentIds, componentIds);
        return this;
    }

    @Override
    public ArtifactResolutionQuery forComponents(ComponentIdentifier... componentIds) {
        CollectionUtils.addAll(this.componentIds, componentIds);
        return this;
    }

    @Override
    public ArtifactResolutionQuery forModule(@Nonnull String group, @Nonnull String name, @Nonnull String version) {
        componentIds.add(DefaultModuleComponentIdentifier
                .newId(DefaultModuleIdentifier.newId(group, name), version));
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ArtifactResolutionQuery withArtifacts(Class<? extends Component> componentType, Class<? extends Artifact>... artifactTypes) {
        return withArtifacts(componentType, Arrays.asList(artifactTypes));
    }

    @Override
    public ArtifactResolutionQuery withArtifacts(Class<? extends Component> componentType, Collection<Class<? extends Artifact>> artifactTypes) {
        if (this.componentType != null) {
            throw new IllegalStateException("Cannot specify component type multiple times.");
        }
        this.componentType = componentType;
        this.artifactTypes.addAll(artifactTypes);
        return this;
    }

    @Override
    public ArtifactResolutionResult execute() {
        if (componentType == null) {
            throw new IllegalStateException("Must specify component type and artifacts to query.");
        }
        List<? extends ResolutionAwareRepository> repositories = repositoriesSupplier.get();
        ConfigurationInternal detachedConfiguration = configurationContainer.detachedConfiguration();
        ResolutionStrategyInternal resolutionStrategy = detachedConfiguration.getResolutionStrategy();
        ComponentResolvers componentResolvers = ivyFactory.create(detachedConfiguration.getName(), resolutionStrategy, repositories, metadataHandler.getComponentMetadataProcessorFactory(), ImmutableAttributes.EMPTY, null, attributesFactory, componentMetadataSupplierRuleExecutor);
        ComponentMetaDataResolver componentMetaDataResolver = componentResolvers.getComponentResolver();
        ArtifactResolver artifactResolver = new ErrorHandlingArtifactResolver(componentResolvers.getArtifactResolver());
        return createResult(componentMetaDataResolver, artifactResolver);
    }

    private ArtifactResolutionResult createResult(ComponentMetaDataResolver componentMetaDataResolver, ArtifactResolver artifactResolver) {
        Set<ComponentResult> componentResults = Sets.newHashSet();

        for (ComponentIdentifier componentId : componentIds) {
            try {
                ComponentIdentifier validId = validateComponentIdentifier(componentId);
                componentResults.add(buildComponentResult(validId, componentMetaDataResolver, artifactResolver));
            } catch (Exception t) {
                componentResults.add(new DefaultUnresolvedComponentResult(componentId, t));
            }
        }

        return new DefaultArtifactResolutionResult(componentResults);
    }

    private ComponentIdentifier validateComponentIdentifier(ComponentIdentifier componentId) {
        if (componentId instanceof ModuleComponentIdentifier) {
            return componentId;
        }
        if (componentId instanceof ProjectComponentIdentifier) {
            throw new IllegalArgumentException(String.format("Cannot query artifacts for a project component (%s).", componentId.getDisplayName()));
        }

        throw new IllegalArgumentException(String.format("Cannot resolve the artifacts for component %s with unsupported type %s.", componentId.getDisplayName(), componentId.getClass().getName()));
    }

    private ComponentArtifactsResult buildComponentResult(ComponentIdentifier componentId, ComponentMetaDataResolver componentMetaDataResolver, ArtifactResolver artifactResolver) {
        BuildableComponentResolveResult moduleResolveResult = new DefaultBuildableComponentResolveResult();
        componentMetaDataResolver.resolve(componentId, DefaultComponentOverrideMetadata.EMPTY, moduleResolveResult);
        ComponentResolveMetadata component = moduleResolveResult.getMetadata();
        DefaultComponentArtifactsResult componentResult = new DefaultComponentArtifactsResult(component.getId());
        for (Class<? extends Artifact> artifactType : artifactTypes) {
            addArtifacts(componentResult, artifactType, component, artifactResolver);
        }
        return componentResult;
    }

    private <T extends Artifact> void addArtifacts(DefaultComponentArtifactsResult artifacts, Class<T> type, ComponentResolveMetadata component, ArtifactResolver artifactResolver) {
        BuildableArtifactSetResolveResult artifactSetResolveResult = new DefaultBuildableArtifactSetResolveResult();
        artifactResolver.resolveArtifactsWithType(component, convertType(type), artifactSetResolveResult);

        for (ComponentArtifactMetadata artifactMetaData : artifactSetResolveResult.getResult()) {
            BuildableArtifactResolveResult resolveResult = new DefaultBuildableArtifactResolveResult();
            artifactResolver.resolveArtifact(artifactMetaData, component.getSources(), resolveResult);
            if (resolveResult.getFailure() != null) {
                artifacts.addArtifact(new DefaultUnresolvedArtifactResult(artifactMetaData.getId(), type, resolveResult.getFailure()));
            } else {
                artifacts.addArtifact(ivyFactory.verifiedArtifact(new DefaultResolvedArtifactResult(artifactMetaData.getId(), ImmutableAttributes.EMPTY, Collections.emptyList(), Describables.of(component.getId().getDisplayName()), type, resolveResult.getResult())));
            }
        }
    }

    private <T extends Artifact> ArtifactType convertType(Class<T> requestedType) {
        return componentTypeRegistry.getComponentRegistration(componentType).getArtifactType(requestedType);
    }
}
