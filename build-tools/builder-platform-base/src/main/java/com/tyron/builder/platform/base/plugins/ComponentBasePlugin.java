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

package com.tyron.builder.platform.base.plugins;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.language.base.plugins.LifecycleBasePlugin;
import com.tyron.builder.model.Model;
import com.tyron.builder.model.Mutate;
import com.tyron.builder.model.RuleSource;
import com.tyron.builder.model.Validate;
import com.tyron.builder.model.internal.core.Hidden;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;
import com.tyron.builder.model.internal.core.NodeInitializerRegistry;
import com.tyron.builder.model.internal.manage.binding.StructBindingsStore;
import com.tyron.builder.model.internal.manage.schema.extract.FactoryBasedStructNodeInitializerExtractionStrategy;
import com.tyron.builder.platform.base.ComponentSpec;
import com.tyron.builder.platform.base.ComponentSpecContainer;
import com.tyron.builder.platform.base.ComponentType;
import com.tyron.builder.platform.base.TypeBuilder;
import com.tyron.builder.platform.base.component.internal.ComponentSpecFactory;
import com.tyron.builder.platform.base.component.internal.DefaultComponentSpec;
import com.tyron.builder.platform.base.internal.ComponentSpecInternal;

/**
 * Base plugin for {@link ComponentSpec} support.
 * <p>
 * - Registers the infrastructure to support the base {@link ComponentSpec} type and extensions
 * to this type.
 */
@Incubating
public class ComponentBasePlugin implements Plugin<BuildProject> {

    @Override
    public void apply(BuildProject project) {
        project.getPluginManager().apply(LifecycleBasePlugin.class);
    }

    @SuppressWarnings("UnusedDeclaration")
    static class PluginRules extends RuleSource {
        @Model
        void components(ComponentSpecContainer componentSpecs) {
        }

        @Hidden
        @Model
        ComponentSpecFactory componentSpecFactory(ProjectIdentifier projectIdentifier,
                                                  Instantiator instantiator,
                                                  ObjectFactory objectFactory,
                                                  NamedEntityInstantiator<Task> taskInstantiator,
                                                  CollectionCallbackActionDecorator collectionCallbackActionDecorator,
                                                  ServiceRegistry serviceRegistry) {
            DomainObjectCollectionFactory domainObjectCollectionFactory =
                    serviceRegistry.get(DomainObjectCollectionFactory.class);
            return new ComponentSpecFactory(projectIdentifier, instantiator, taskInstantiator,
                    objectFactory, collectionCallbackActionDecorator,
                    domainObjectCollectionFactory);
        }

        @ComponentType
        void registerComponentSpec(TypeBuilder<ComponentSpec> builder) {
            builder.defaultImplementation(DefaultComponentSpec.class);
            builder.internalView(ComponentSpecInternal.class);
        }

        @Mutate
        void registerNodeInitializerExtractors(NodeInitializerRegistry nodeInitializerRegistry,
                                               ComponentSpecFactory componentSpecFactory,
                                               StructBindingsStore bindingsStore) {
            nodeInitializerRegistry.registerStrategy(
                    new FactoryBasedStructNodeInitializerExtractionStrategy<>(
                            componentSpecFactory, bindingsStore));
        }

        @Validate
        void validateComponentSpecRegistrations(ComponentSpecFactory instanceFactory) {
            instanceFactory.validateRegistrations();
        }
    }
}
