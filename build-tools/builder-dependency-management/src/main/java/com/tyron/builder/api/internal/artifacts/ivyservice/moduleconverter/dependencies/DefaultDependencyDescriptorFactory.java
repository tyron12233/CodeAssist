/*
 * Copyright 2007-2008 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.ModuleDependency;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.dependencies.DependencyConstraintInternal;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentSelector;
import com.tyron.builder.internal.component.model.LocalComponentDependencyMetadata;
import com.tyron.builder.internal.component.model.LocalOriginDependencyMetadata;
import com.tyron.builder.util.internal.WrapUtil;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class DefaultDependencyDescriptorFactory implements DependencyDescriptorFactory {
    private final List<IvyDependencyDescriptorFactory> dependencyDescriptorFactories;

    public DefaultDependencyDescriptorFactory(IvyDependencyDescriptorFactory... dependencyDescriptorFactories) {
        this.dependencyDescriptorFactories = WrapUtil.toList(dependencyDescriptorFactories);
    }

    @Override
    public LocalOriginDependencyMetadata createDependencyDescriptor(ComponentIdentifier componentId, @Nullable String clientConfiguration, @Nullable AttributeContainer attributes, ModuleDependency dependency) {
        IvyDependencyDescriptorFactory factoryInternal = findFactoryForDependency(dependency);
        return factoryInternal.createDependencyDescriptor(componentId, clientConfiguration, attributes, dependency);
    }

    @Override
    public LocalOriginDependencyMetadata createDependencyConstraintDescriptor(ComponentIdentifier componentId, String clientConfiguration, AttributeContainer attributes, DependencyConstraint dependencyConstraint) {
        ModuleComponentSelector selector = DefaultModuleComponentSelector.newSelector(
            DefaultModuleIdentifier.newId(nullToEmpty(dependencyConstraint.getGroup()), nullToEmpty(dependencyConstraint.getName())), dependencyConstraint.getVersionConstraint(), dependencyConstraint.getAttributes(), ImmutableList.of());
        return new LocalComponentDependencyMetadata(componentId, selector, clientConfiguration, attributes, dependencyConstraint.getAttributes(), null,
                Collections.emptyList(), Collections.emptyList(), ((DependencyConstraintInternal)dependencyConstraint).isForce(), false, false, true, false, dependencyConstraint.getReason());
    }

    private IvyDependencyDescriptorFactory findFactoryForDependency(ModuleDependency dependency) {
        for (IvyDependencyDescriptorFactory ivyDependencyDescriptorFactory : dependencyDescriptorFactories) {
            if (ivyDependencyDescriptorFactory.canConvert(dependency)) {
                return ivyDependencyDescriptorFactory;
            }
        }
        throw new InvalidUserDataException("Can't map dependency of type: " + dependency.getClass());
    }

    private String nullToEmpty(@Nullable String input) {
        return input == null ? "" : input;
    }
}
