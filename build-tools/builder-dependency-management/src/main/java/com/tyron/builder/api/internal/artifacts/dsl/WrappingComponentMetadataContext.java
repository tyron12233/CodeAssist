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

package com.tyron.builder.api.internal.artifacts.dsl;

import com.tyron.builder.api.internal.artifacts.dsl.dependencies.PlatformSupport;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ComponentMetadataDetailsAdapter;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.DependencyConstraintMetadataImpl;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.DirectDependencyMetadataImpl;
import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.VariantDerivationStrategy;

import com.tyron.builder.api.artifacts.ComponentMetadataContext;
import com.tyron.builder.api.artifacts.ComponentMetadataDetails;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.typeconversion.NotationParser;

class WrappingComponentMetadataContext implements ComponentMetadataContext {


    private final ModuleComponentResolveMetadata metadata;
    private final Instantiator instantiator;
    private final NotationParser<Object, DirectDependencyMetadataImpl> dependencyMetadataNotationParser;
    private final NotationParser<Object, DependencyConstraintMetadataImpl> dependencyConstraintMetadataNotationParser;
    private final NotationParser<Object, ComponentIdentifier> componentIdentifierParser;
    private final PlatformSupport platformSupport;
    private final MetadataDescriptorFactory descriptorFactory;

    private MutableModuleComponentResolveMetadata mutableMetadata;
    private ComponentMetadataDetails details;

    public WrappingComponentMetadataContext(ModuleComponentResolveMetadata metadata, Instantiator instantiator,
                                            NotationParser<Object, DirectDependencyMetadataImpl> dependencyMetadataNotationParser,
                                            NotationParser<Object, DependencyConstraintMetadataImpl> dependencyConstraintMetadataNotationParser,
                                            NotationParser<Object, ComponentIdentifier> componentIdentifierParser,
                                            PlatformSupport platformSupport) {
        this.metadata = metadata;
        this.instantiator = instantiator;
        this.dependencyMetadataNotationParser = dependencyMetadataNotationParser;
        this.dependencyConstraintMetadataNotationParser = dependencyConstraintMetadataNotationParser;
        this.componentIdentifierParser = componentIdentifierParser;
        this.platformSupport = platformSupport;
        this.descriptorFactory = new MetadataDescriptorFactory(metadata);
    }

    @Override
    public <T> T getDescriptor(Class<T> descriptorClass) {
        return descriptorFactory.createDescriptor(descriptorClass);
    }

    @Override
    public ComponentMetadataDetails getDetails() {
        createMutableMetadataIfNeeded();
        if (details == null) {
            details = instantiator.newInstance(ComponentMetadataDetailsAdapter.class, mutableMetadata, instantiator, dependencyMetadataNotationParser, dependencyConstraintMetadataNotationParser, componentIdentifierParser, platformSupport);
        }
        return details;
    }

    VariantDerivationStrategy getVariantDerivationStrategy() {
        return metadata.getVariantDerivationStrategy();
    }

    ModuleComponentResolveMetadata getImmutableMetadataWithDerivationStrategy(VariantDerivationStrategy variantDerivationStrategy) {
        // We need to create a copy or the rules will be added to the wrong container
        return createMutableMetadataIfNeeded().asImmutable()
            .withDerivationStrategy(variantDerivationStrategy);
    }

    private MutableModuleComponentResolveMetadata createMutableMetadataIfNeeded() {
        if (mutableMetadata == null) {
            mutableMetadata = metadata.asMutable();
        }
        return mutableMetadata;
    }
}
