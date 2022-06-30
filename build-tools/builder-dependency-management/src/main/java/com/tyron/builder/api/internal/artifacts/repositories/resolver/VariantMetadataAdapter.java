/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.repositories.resolver;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.MutableVariantFilesMetadata;
import com.tyron.builder.api.capabilities.MutableCapabilitiesMetadata;
import com.tyron.builder.api.artifacts.DependencyConstraintMetadata;
import com.tyron.builder.api.artifacts.DependencyConstraintsMetadata;
import com.tyron.builder.api.artifacts.DirectDependenciesMetadata;
import com.tyron.builder.api.artifacts.DirectDependencyMetadata;
import com.tyron.builder.api.artifacts.VariantMetadata;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.VariantMetadataRules;
import com.tyron.builder.internal.component.model.VariantResolveMetadata;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.typeconversion.NotationParser;

/**
 * Adapts a mutable module component resolve metadata instance into a form that is suitable
 * for mutation through the Gradle DSL: we don't want to expose all the resolve component
 * metadata methods, only those which make sense, and that we can reason about safely. The adapter
 * is responsible for targetting variants subject to a rule.
 */
public class VariantMetadataAdapter implements VariantMetadata {
    private final Spec<? super VariantResolveMetadata> spec;
    private final MutableModuleComponentResolveMetadata metadata;
    private final Instantiator instantiator;
    private final NotationParser<Object, DirectDependencyMetadata> dependencyMetadataNotationParser;
    private final NotationParser<Object, DependencyConstraintMetadata> dependencyConstraintMetadataNotationParser;

    public VariantMetadataAdapter(Spec<? super VariantResolveMetadata> spec,
                                  MutableModuleComponentResolveMetadata metadata, Instantiator instantiator,
                                  NotationParser<Object, DirectDependencyMetadata> dependencyMetadataNotationParser,
                                  NotationParser<Object, DependencyConstraintMetadata> dependencyConstraintMetadataNotationParser) {
        this.spec = spec;
        this.metadata = metadata;
        this.instantiator = instantiator;
        this.dependencyMetadataNotationParser = dependencyMetadataNotationParser;
        this.dependencyConstraintMetadataNotationParser = dependencyConstraintMetadataNotationParser;
    }

    @Override
    public void withDependencies(Action<? super DirectDependenciesMetadata> action) {
        metadata.getVariantMetadataRules().addDependencyAction(instantiator, dependencyMetadataNotationParser, dependencyConstraintMetadataNotationParser, new VariantMetadataRules.VariantAction<>(spec, action));
    }

    @Override
    public void withDependencyConstraints(Action<? super DependencyConstraintsMetadata> action) {
        metadata.getVariantMetadataRules().addDependencyConstraintAction(instantiator, dependencyMetadataNotationParser, dependencyConstraintMetadataNotationParser, new VariantMetadataRules.VariantAction<>(spec, action));
    }

    @Override
    public void withCapabilities(Action<? super MutableCapabilitiesMetadata> action) {
        metadata.getVariantMetadataRules().addCapabilitiesAction(new VariantMetadataRules.VariantAction<>(spec, action));
    }

    @Override
    public void withFiles(Action<? super MutableVariantFilesMetadata> action) {
        metadata.getVariantMetadataRules().addVariantFilesAction(new VariantMetadataRules.VariantAction<>(spec, action));
    }

    @Override
    public VariantMetadata attributes(Action<? super AttributeContainer> action) {
        metadata.getVariantMetadataRules().addAttributesAction(metadata.getAttributesFactory(), new VariantMetadataRules.VariantAction<>(spec, action));
        return this;
    }

    @Override
    public AttributeContainer getAttributes() {
        return metadata.getAttributes();
    }

}
