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

import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory;

import com.tyron.builder.api.artifacts.query.ArtifactResolutionQuery;
import com.tyron.builder.api.internal.artifacts.GlobalDependencyResolutionRules;
import com.tyron.builder.api.internal.artifacts.RepositoriesSupplier;

import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.component.ComponentTypeRegistry;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataSupplierRuleExecutor;

public class DefaultArtifactResolutionQueryFactory implements ArtifactResolutionQueryFactory {
    private final ConfigurationContainerInternal configurationContainer;
    private final RepositoriesSupplier repositoriesSupplier;
    private final ResolveIvyFactory ivyFactory;
    private final GlobalDependencyResolutionRules metadataHandler;
    private final ComponentTypeRegistry componentTypeRegistry;
    private final ImmutableAttributesFactory attributesFactory;
    private final ComponentMetadataSupplierRuleExecutor componentMetadataSupplierRuleExecutor;

    public DefaultArtifactResolutionQueryFactory(ConfigurationContainerInternal configurationContainer,
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
    public ArtifactResolutionQuery createArtifactResolutionQuery() {
        return new DefaultArtifactResolutionQuery(configurationContainer, repositoriesSupplier, ivyFactory, metadataHandler, componentTypeRegistry, attributesFactory, componentMetadataSupplierRuleExecutor);
    }
}
