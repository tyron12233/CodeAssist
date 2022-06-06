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

import com.tyron.builder.api.artifacts.ModuleDependency;
import com.tyron.builder.api.artifacts.ProjectDependency;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.artifacts.dependencies.ProjectDependencyInternal;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.internal.component.local.model.DefaultProjectComponentSelector;
import com.tyron.builder.internal.component.local.model.DslOriginDependencyMetadataWrapper;
import com.tyron.builder.internal.component.model.ExcludeMetadata;
import com.tyron.builder.internal.component.model.LocalComponentDependencyMetadata;
import com.tyron.builder.internal.component.model.LocalOriginDependencyMetadata;

import javax.annotation.Nullable;
import java.util.List;

public class ProjectIvyDependencyDescriptorFactory extends AbstractIvyDependencyDescriptorFactory {

    public ProjectIvyDependencyDescriptorFactory(ExcludeRuleConverter excludeRuleConverter) {
        super(excludeRuleConverter);
    }

    @Override
    public LocalOriginDependencyMetadata createDependencyDescriptor(ComponentIdentifier componentId, @Nullable String clientConfiguration, AttributeContainer clientAttributes, ModuleDependency dependency) {
        ProjectDependencyInternal projectDependency = (ProjectDependencyInternal) dependency;
        ComponentSelector selector = DefaultProjectComponentSelector.newSelector(projectDependency.getDependencyProject(),
                ((AttributeContainerInternal)projectDependency.getAttributes()).asImmutable(),
                projectDependency.getRequestedCapabilities());

        List<ExcludeMetadata> excludes = convertExcludeRules(dependency.getExcludeRules());
        LocalComponentDependencyMetadata dependencyMetaData = new LocalComponentDependencyMetadata(
            componentId,
            selector,
            clientConfiguration,
            clientAttributes,
            dependency.getAttributes(),
            projectDependency.getTargetConfiguration(),
            convertArtifacts(dependency.getArtifacts()),
            excludes,
            false, false, dependency.isTransitive(), false, dependency.isEndorsingStrictVersions(), dependency.getReason());
        return new DslOriginDependencyMetadataWrapper(dependencyMetaData, dependency);
    }

    @Override
    public boolean canConvert(ModuleDependency dependency) {
        return dependency instanceof ProjectDependency;
    }
}
