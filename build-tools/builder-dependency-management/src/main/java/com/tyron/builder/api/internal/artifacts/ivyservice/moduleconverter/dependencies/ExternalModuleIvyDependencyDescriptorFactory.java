/*
 * Copyright 2009 the original author or authors.
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

import com.tyron.builder.api.artifacts.ExternalModuleDependency;
import com.tyron.builder.api.artifacts.ModuleDependency;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.VersionConstraintInternal;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentSelector;
import com.tyron.builder.internal.component.local.model.DslOriginDependencyMetadataWrapper;
import com.tyron.builder.internal.component.model.ExcludeMetadata;
import com.tyron.builder.internal.component.model.LocalComponentDependencyMetadata;
import com.tyron.builder.internal.component.model.LocalOriginDependencyMetadata;

import javax.annotation.Nullable;
import java.util.List;

public class ExternalModuleIvyDependencyDescriptorFactory extends AbstractIvyDependencyDescriptorFactory {
    public ExternalModuleIvyDependencyDescriptorFactory(ExcludeRuleConverter excludeRuleConverter) {
        super(excludeRuleConverter);
    }

    @Override
    public LocalOriginDependencyMetadata createDependencyDescriptor(ComponentIdentifier componentId, @Nullable String clientConfiguration, @Nullable AttributeContainer clientAttributes, ModuleDependency dependency) {
        ExternalModuleDependency externalModuleDependency = (ExternalModuleDependency) dependency;
        boolean force = externalModuleDependency.isForce();
        boolean changing = externalModuleDependency.isChanging();
        boolean transitive = externalModuleDependency.isTransitive();

        ModuleComponentSelector selector = DefaultModuleComponentSelector.newSelector(
                DefaultModuleIdentifier.newId(nullToEmpty(dependency.getGroup()), nullToEmpty(dependency.getName())),
                ((VersionConstraintInternal) externalModuleDependency.getVersionConstraint()).asImmutable(),
                dependency.getAttributes(),
                dependency.getRequestedCapabilities());

        List<ExcludeMetadata> excludes = convertExcludeRules(dependency.getExcludeRules());
        LocalComponentDependencyMetadata dependencyMetaData = new LocalComponentDependencyMetadata(
                componentId, selector, clientConfiguration, clientAttributes,
                dependency.getAttributes(),
                dependency.getTargetConfiguration(),
                convertArtifacts(dependency.getArtifacts()),
                excludes, force, changing, transitive, false, dependency.isEndorsingStrictVersions(), dependency.getReason());
        return new DslOriginDependencyMetadataWrapper(dependencyMetaData, dependency);
    }

    private String nullToEmpty(@Nullable String input) {
        return input == null ? "" : input;
    }

    @Override
    public boolean canConvert(ModuleDependency dependency) {
        return dependency instanceof ExternalModuleDependency;
    }
}
