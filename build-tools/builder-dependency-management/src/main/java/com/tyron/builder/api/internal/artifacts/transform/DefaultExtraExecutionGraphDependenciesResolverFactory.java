/*
 * Copyright 2018 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.internal.artifacts.configurations.ResolutionResultProvider;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.result.ResolutionResult;
import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;

public class DefaultExtraExecutionGraphDependenciesResolverFactory implements ExtraExecutionGraphDependenciesResolverFactory {
    public static final TransformUpstreamDependenciesResolver NO_DEPENDENCIES_RESOLVER = transformationStep -> DefaultTransformUpstreamDependenciesResolver.NO_DEPENDENCIES;

    private final DomainObjectContext owner;
    private final FilteredResultFactory filteredResultFactory;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;
    private final ResolutionResultProvider<ResolutionResult> resolutionResultProvider;

    public DefaultExtraExecutionGraphDependenciesResolverFactory(ResolutionResultProvider<ResolutionResult> resolutionResultProvider,
                                                                 DomainObjectContext owner,
                                                                 CalculatedValueContainerFactory calculatedValueContainerFactory,
                                                                 FilteredResultFactory filteredResultFactory) {
        this.resolutionResultProvider = resolutionResultProvider;
        this.owner = owner;
        this.filteredResultFactory = filteredResultFactory;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
    }

    @Override
    public TransformUpstreamDependenciesResolver create(ComponentIdentifier componentIdentifier, Transformation transformation) {
        if (!transformation.requiresDependencies()) {
            return NO_DEPENDENCIES_RESOLVER;
        }
        return new DefaultTransformUpstreamDependenciesResolver(componentIdentifier, resolutionResultProvider, owner, filteredResultFactory, calculatedValueContainerFactory);
    }
}
