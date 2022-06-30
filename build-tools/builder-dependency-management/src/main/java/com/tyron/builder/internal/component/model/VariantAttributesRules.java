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
package com.tyron.builder.internal.component.model;

import com.google.common.collect.Lists;
import com.tyron.builder.internal.component.external.model.VariantMetadataRules;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;

import java.util.List;

/**
 * A set of rules provided by the build script author
 * (as {@link Action &lt;? super AttributeContainer&gt;}
 * that are applied on the attributes defined in variant/configuration metadata. The rules are applied
 * in the {@link #execute(VariantResolveMetadata, AttributeContainerInternal)} method when the attributes of a variant are needed during dependency resolution.
 */
public class VariantAttributesRules {
    private final ImmutableAttributesFactory attributesFactory;
    private final List<VariantMetadataRules.VariantAction<? super AttributeContainer>> actions = Lists.newLinkedList();

    public VariantAttributesRules(ImmutableAttributesFactory attributesFactory) {
        this.attributesFactory = attributesFactory;
    }

    public void addAttributesAction(VariantMetadataRules.VariantAction<? super AttributeContainer> action) {
        actions.add(action);
    }

    public ImmutableAttributes execute(VariantResolveMetadata variant, AttributeContainerInternal attributes) {
        if (attributes == null) {
            attributes = attributesFactory.mutable();
        } else {
            attributes = attributesFactory.mutable(attributes);
        }
        for (VariantMetadataRules.VariantAction<? super AttributeContainer> action : actions) {
            action.maybeExecute(variant, attributes);
        }
        return attributes.asImmutable();
    }
}
