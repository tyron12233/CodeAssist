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

package com.tyron.builder.api.internal.artifacts.type;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.artifacts.type.ArtifactTypeContainer;
import com.tyron.builder.api.artifacts.type.ArtifactTypeDefinition;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.AbstractValidatingNamedDomainObjectContainer;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.internal.reflect.Instantiator;

import java.util.Set;

public class DefaultArtifactTypeContainer extends AbstractValidatingNamedDomainObjectContainer<ArtifactTypeDefinition> implements ArtifactTypeContainer {
    private final ImmutableAttributesFactory attributesFactory;

    public DefaultArtifactTypeContainer(Instantiator instantiator, ImmutableAttributesFactory attributesFactory, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(ArtifactTypeDefinition.class, instantiator, callbackActionDecorator);
        this.attributesFactory = attributesFactory;
    }

    @Override
    protected ArtifactTypeDefinition doCreate(final String name) {
        return getInstantiator().newInstance(DefaultArtifactTypeDefinition.class, name, attributesFactory);
    }

    public static class DefaultArtifactTypeDefinition implements ArtifactTypeDefinition {
        private final String name;
        private final AttributeContainer attributes;

        public DefaultArtifactTypeDefinition(String name, ImmutableAttributesFactory attributesFactory) {
            this.name = name;
            attributes = attributesFactory.mutable();
        }

        @Override
        public Set<String> getFileNameExtensions() {
            return ImmutableSet.of(name);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public AttributeContainer getAttributes() {
            return attributes;
        }
    }
}
