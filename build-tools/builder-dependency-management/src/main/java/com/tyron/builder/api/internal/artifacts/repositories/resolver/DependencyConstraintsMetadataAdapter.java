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

import com.tyron.builder.api.artifacts.DependencyConstraintMetadata;
import com.tyron.builder.api.artifacts.DependencyConstraintsMetadata;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.typeconversion.NotationParser;

import java.util.List;

import com.tyron.builder.internal.component.model.DependencyMetadata;

public class DependencyConstraintsMetadataAdapter extends AbstractDependenciesMetadataAdapter<DependencyConstraintMetadata> implements DependencyConstraintsMetadata {

    public DependencyConstraintsMetadataAdapter(ImmutableAttributesFactory attributesFactory,
                                                List<DependencyMetadata> dependenciesMetadata,
                                                Instantiator instantiator,
                                                NotationParser<Object, DependencyConstraintMetadata> dependencyConstraintsNotationParser) {
        super(attributesFactory, dependenciesMetadata, instantiator, dependencyConstraintsNotationParser);
    }

    @Override
    protected Class<? extends DependencyConstraintMetadata> adapterImplementationType() {
        return DependencyConstraintMetadataAdapter.class;
    }

    @Override
    protected boolean isConstraint() {
        return true;
    }

    @Override
    protected boolean isEndorsingStrictVersions(DependencyConstraintMetadata details) {
        return false;
    }

}
