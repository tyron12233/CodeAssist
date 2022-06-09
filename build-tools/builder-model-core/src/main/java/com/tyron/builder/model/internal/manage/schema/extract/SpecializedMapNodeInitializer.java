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

package com.tyron.builder.model.internal.manage.schema.extract;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import com.tyron.builder.api.Action;
import com.tyron.builder.internal.BiAction;
import com.tyron.builder.model.internal.core.ChildNodeInitializerStrategy;
import com.tyron.builder.model.internal.core.ChildNodeInitializerStrategyAccessor;
import com.tyron.builder.model.internal.core.ChildNodeInitializerStrategyAccessors;
import com.tyron.builder.model.internal.core.DirectNodeInputUsingModelAction;
import com.tyron.builder.model.internal.core.DirectNodeNoInputsModelAction;
import com.tyron.builder.model.internal.core.ModelAction;
import com.tyron.builder.model.internal.core.ModelActionRole;
import com.tyron.builder.model.internal.core.ModelMapModelProjection;
import com.tyron.builder.model.internal.core.ModelReference;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.core.NodeBackedModelMap;
import com.tyron.builder.model.internal.core.NodeInitializer;
import com.tyron.builder.model.internal.core.NodeInitializerRegistry;
import com.tyron.builder.model.internal.core.SpecializedModelMapProjection;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.manage.schema.SpecializedMapSchema;
import com.tyron.builder.model.internal.type.ModelType;

public class SpecializedMapNodeInitializer<T, E> implements NodeInitializer {
    private final SpecializedMapSchema<T, E> schema;

    public SpecializedMapNodeInitializer(SpecializedMapSchema<T, E> schema) {
        this.schema = schema;
    }

    @Override
    public Multimap<ModelActionRole, ModelAction> getActions(ModelReference<?> subject, ModelRuleDescriptor descriptor) {
        return getActions(subject, descriptor, schema);
    }

    public static <T, E> Multimap<ModelActionRole, ModelAction> getActions(ModelReference<?> subject, ModelRuleDescriptor descriptor, final SpecializedMapSchema<T, E> schema) {
        return ImmutableSetMultimap.<ModelActionRole, ModelAction>builder()
            .put(ModelActionRole.Discover, DirectNodeNoInputsModelAction.of(subject, descriptor,
                new Action<MutableModelNode>() {
                    @Override
                    public void execute(MutableModelNode modelNode) {
                        ChildNodeInitializerStrategyAccessor<E> strategyAccessor = ChildNodeInitializerStrategyAccessors.fromPrivateData();
                        Class<? extends T> implementationType = schema.getImplementationType().asSubclass(schema.getType().getConcreteClass());
                        modelNode.addProjection(new SpecializedModelMapProjection<T, E>(schema.getType(), schema.getElementType(), implementationType, strategyAccessor));
                        modelNode.addProjection(ModelMapModelProjection.managed(schema.getType(), schema.getElementType(), strategyAccessor));
                    }
                }
            ))
            .put(ModelActionRole.Create, DirectNodeInputUsingModelAction.of(subject, descriptor,
                ModelReference.of(NodeInitializerRegistry.class),
                    (modelNode, nodeInitializerRegistry) -> {
                        ChildNodeInitializerStrategy<E> childFactory = NodeBackedModelMap.createUsingRegistry(nodeInitializerRegistry);
                        modelNode.setPrivateData(ModelType.of(ChildNodeInitializerStrategy.class), childFactory);
                    }))
            .build();
    }
}
