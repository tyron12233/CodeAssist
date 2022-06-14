/*
 * Copyright 2013 the original author or authors.
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

import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.ModuleDependency;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.internal.component.model.LocalOriginDependencyMetadata;

import javax.annotation.Nullable;

public interface DependencyDescriptorFactory {
    LocalOriginDependencyMetadata createDependencyDescriptor(ComponentIdentifier componentId, @Nullable String clientConfiguration, @Nullable AttributeContainer attributes, ModuleDependency dependency);
    LocalOriginDependencyMetadata createDependencyConstraintDescriptor(ComponentIdentifier componentId, String clientConfiguration, AttributeContainer attributes, DependencyConstraint dependencyConstraint);
}
