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

package com.tyron.builder.platform.base.component.internal;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.language.base.sources.BaseLanguageSourceSet;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.model.internal.typeregistration.BaseInstanceFactory;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.ComponentSpec;
import com.tyron.builder.platform.base.binary.BaseBinarySpec;
import com.tyron.builder.platform.base.internal.ComponentSpecIdentifier;
import com.tyron.builder.platform.base.internal.ComponentSpecInternal;
import com.tyron.builder.platform.base.internal.DefaultComponentSpecIdentifier;

import javax.annotation.Nullable;

public class ComponentSpecFactory extends BaseInstanceFactory<ComponentSpec> {
    private final ProjectIdentifier projectIdentifier;

    public ComponentSpecFactory(final ProjectIdentifier projectIdentifier, final Instantiator instantiator, final NamedEntityInstantiator<Task> taskInstantiator, final ObjectFactory objectFactory,
                                final CollectionCallbackActionDecorator collectionCallbackActionDecorator, final DomainObjectCollectionFactory domainObjectCollectionFactory) {
        super(ComponentSpec.class);
        this.projectIdentifier = projectIdentifier;
        registerFactory(DefaultComponentSpec.class, new ImplementationFactory<ComponentSpec, DefaultComponentSpec>() {
            @Override
            public <T extends DefaultComponentSpec> T create(ModelType<? extends ComponentSpec> publicType, ModelType<T> implementationType, String name, MutableModelNode componentNode) {
                ComponentSpecIdentifier id = getId(findOwner(componentNode), name);
                return DefaultComponentSpec.create(publicType.getConcreteClass(), implementationType.getConcreteClass(), id, componentNode);
            }
        });
        registerFactory(BaseBinarySpec.class, new ImplementationFactory<BinarySpec, BaseBinarySpec>() {
            @Override
            public <T extends BaseBinarySpec> T create(ModelType<? extends BinarySpec> publicType, ModelType<T> implementationType, String name, MutableModelNode binaryNode) {
                MutableModelNode componentNode = findOwner(binaryNode);
                ComponentSpecIdentifier id = getId(componentNode, name);
                return BaseBinarySpec.create(
                    publicType.getConcreteClass(),
                    implementationType.getConcreteClass(),
                    id,
                    binaryNode,
                    componentNode,
                    instantiator,
                    taskInstantiator,
                    collectionCallbackActionDecorator,
                    domainObjectCollectionFactory);
            }
        });
        registerFactory(BaseLanguageSourceSet.class, new ImplementationFactory<LanguageSourceSet, BaseLanguageSourceSet>() {
            @Override
            public <T extends BaseLanguageSourceSet> T create(ModelType<? extends LanguageSourceSet> publicType, ModelType<T> implementationType, String sourceSetName, MutableModelNode node) {
                ComponentSpecIdentifier id = getId(findOwner(node), sourceSetName);
                return Cast.uncheckedCast(BaseLanguageSourceSet.create(publicType.getConcreteClass(), implementationType.getConcreteClass(), id, objectFactory));
            }
        });
    }

    @Nullable
    private ComponentSpecIdentifier getId(@Nullable MutableModelNode ownerNode, String name) {
        if (ownerNode != null) {
            ComponentSpecInternal componentSpec = ownerNode.asImmutable(ModelType.of(ComponentSpecInternal.class), null).getInstance();
            return componentSpec.getIdentifier().child(name);
        }

        return new DefaultComponentSpecIdentifier(projectIdentifier.getPath(), name);
    }

    @Nullable
    private MutableModelNode findOwner(MutableModelNode modelNode) {
        MutableModelNode grandparentNode = modelNode.getParent().getParent();
        if (grandparentNode != null && grandparentNode.canBeViewedAs(ModelType.of(ComponentSpecInternal.class))) {
            return grandparentNode;
        }

        return null;
    }
}
