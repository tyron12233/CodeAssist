/*
 * Copyright 2016 the original author or authors.
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

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.artifacts.ConfigurationVariant;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.api.artifacts.PublishArtifactSet;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.ConfigurationVariantInternal;
import com.tyron.builder.api.internal.artifacts.DefaultPublishArtifactSet;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.attributes.ImmutableAttributeContainerWithErrorMessage;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.typeconversion.NotationParser;

import java.util.Collection;
import java.util.List;

public class DefaultVariant implements ConfigurationVariantInternal {
    private final Describable parentDisplayName;
    private final String name;
    private AttributeContainerInternal attributes;
    private final NotationParser<Object, ConfigurablePublishArtifact> artifactNotationParser;
    private final PublishArtifactSet artifacts;
    private Factory<List<PublishArtifact>> lazyArtifacts;

    public DefaultVariant(Describable parentDisplayName,
                          String name,
                          AttributeContainerInternal parentAttributes,
                          NotationParser<Object, ConfigurablePublishArtifact> artifactNotationParser,
                          FileCollectionFactory fileCollectionFactory,
                          ImmutableAttributesFactory cache,
                          DomainObjectCollectionFactory domainObjectCollectionFactory) {
        this.parentDisplayName = parentDisplayName;
        this.name = name;
        attributes = cache.mutable(parentAttributes);
        this.artifactNotationParser = artifactNotationParser;
        artifacts = new DefaultPublishArtifactSet(getAsDescribable(), domainObjectCollectionFactory.newDomainObjectSet(PublishArtifact.class), fileCollectionFactory);
    }

    @Override
    public String getName() {
        return name;
    }

    public OutgoingVariant convertToOutgoingVariant() {
        return new LeafOutgoingVariant(getAsDescribable(), attributes, getArtifacts());
    }

    public void visit(ConfigurationInternal.VariantVisitor visitor, Collection<? extends Capability> capabilities) {
        visitor.visitChildVariant(name, getAsDescribable(), attributes.asImmutable(), capabilities, getArtifacts());
    }

    private DisplayName getAsDescribable() {
        return Describables.of(parentDisplayName, "variant", name);
    }

    @Override
    public AttributeContainerInternal getAttributes() {
        return attributes;
    }

    @Override
    public ConfigurationVariant attributes(Action<? super AttributeContainer> action) {
        action.execute(attributes);
        return this;
    }

    @Override
    public PublishArtifactSet getArtifacts() {
        if (lazyArtifacts != null) {
            artifacts.addAll(lazyArtifacts.create());
            lazyArtifacts = null;
        }
        return artifacts;
    }

    @Override
    public void artifact(Object notation) {
        artifacts.add(artifactNotationParser.parseNotation(notation));
    }

    @Override
    public void artifact(Object notation, Action<? super ConfigurablePublishArtifact> configureAction) {
        ConfigurablePublishArtifact publishArtifact = artifactNotationParser.parseNotation(notation);
        artifacts.add(publishArtifact);
        configureAction.execute(publishArtifact);
    }

    @Override
    public String toString() {
        return getAsDescribable().getDisplayName();
    }

    @Override
    public void artifactsProvider(Factory<List<PublishArtifact>> artifacts) {
        this.lazyArtifacts = artifacts;
    }

    @Override
    public void preventFurtherMutation() {
        attributes = new ImmutableAttributeContainerWithErrorMessage(attributes.asImmutable(), parentDisplayName);
    }
}
