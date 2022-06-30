/*
 * Copyright 2015 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve;

import com.tyron.builder.api.internal.artifacts.configurations.dynamicversion.CachePolicy;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.artifacts.ComponentMetadataProcessorFactory;
import com.tyron.builder.api.internal.artifacts.DefaultModuleVersionIdentifier;

import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadataWrapper;
import com.tyron.builder.internal.component.model.DependencyMetadata;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataSupplierRuleExecutor;
import com.tyron.builder.internal.resolve.resolver.DependencyToComponentIdResolver;
import com.tyron.builder.internal.resolve.result.BuildableComponentIdResolveResult;

import javax.annotation.Nullable;

public class RepositoryChainDependencyToComponentIdResolver implements DependencyToComponentIdResolver {
    private final DynamicVersionResolver dynamicRevisionResolver;
    private final AttributeContainer consumerAttributes;

    public RepositoryChainDependencyToComponentIdResolver(VersionedComponentChooser componentChooser, Transformer<ModuleComponentResolveMetadata, RepositoryChainModuleResolution> metaDataFactory, VersionParser versionParser, AttributeContainer consumerAttributes, ImmutableAttributesFactory attributesFactory, ComponentMetadataProcessorFactory componentMetadataProcessorFactory, ComponentMetadataSupplierRuleExecutor componentMetadataSupplierRuleExecutor, CachePolicy cachePolicy) {
        this.dynamicRevisionResolver = new DynamicVersionResolver(componentChooser, versionParser, metaDataFactory, attributesFactory, componentMetadataProcessorFactory, componentMetadataSupplierRuleExecutor, cachePolicy);
        this.consumerAttributes = consumerAttributes;
    }

    public void add(ModuleComponentRepository repository) {
        dynamicRevisionResolver.add(repository);
    }

    @Override
    public void resolve(DependencyMetadata dependency, VersionSelector acceptor, @Nullable VersionSelector rejector, BuildableComponentIdResolveResult result) {
        ComponentSelector componentSelector = dependency.getSelector();
        if (componentSelector instanceof ModuleComponentSelector) {
            ModuleComponentSelector module = (ModuleComponentSelector) componentSelector;
            if (acceptor.isDynamic()) {
                dynamicRevisionResolver.resolve(toModuleDependencyMetadata(dependency), acceptor, rejector, consumerAttributes, result);
            } else {
                String version = acceptor.getSelector();
                ModuleIdentifier moduleId = module.getModuleIdentifier();
                ModuleComponentIdentifier id = DefaultModuleComponentIdentifier.newId(moduleId, version);
                ModuleVersionIdentifier mvId = DefaultModuleVersionIdentifier.newId(moduleId, version);
                if (rejector != null && rejector.accept(version)) {
                    result.rejected(id, mvId);
                } else {
                    result.resolved(id, mvId);
                }
            }
        }
    }

    private ModuleDependencyMetadata toModuleDependencyMetadata(DependencyMetadata dependency) {
        if (dependency instanceof ModuleDependencyMetadata) {
            return (ModuleDependencyMetadata) dependency;
        }
        if (dependency.getSelector() instanceof ModuleComponentSelector) {
            return new ModuleDependencyMetadataWrapper(dependency);
        }
        throw new IllegalArgumentException("Not a module dependency: " + dependency);

    }
}
