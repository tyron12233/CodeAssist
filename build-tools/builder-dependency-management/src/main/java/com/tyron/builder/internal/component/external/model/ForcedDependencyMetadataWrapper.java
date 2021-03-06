/*
 * Copyright 2018 the original author or authors.
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
package com.tyron.builder.internal.component.external.model;

import com.tyron.builder.api.artifacts.VersionConstraint;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.attributes.AttributesSchemaInternal;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.internal.component.local.model.DefaultProjectDependencyMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.component.model.DependencyMetadata;
import com.tyron.builder.internal.component.model.ExcludeMetadata;
import com.tyron.builder.internal.component.model.ForcingDependencyMetadata;
import com.tyron.builder.internal.component.model.IvyArtifactName;

import java.util.Collection;
import java.util.List;

public class ForcedDependencyMetadataWrapper implements ForcingDependencyMetadata, ModuleDependencyMetadata {
    private final ModuleDependencyMetadata delegate;

    public ForcedDependencyMetadataWrapper(ModuleDependencyMetadata delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModuleComponentSelector getSelector() {
        return delegate.getSelector();
    }

    @Override
    public ModuleDependencyMetadata withRequestedVersion(VersionConstraint requestedVersion) {
        return new ForcedDependencyMetadataWrapper(delegate.withRequestedVersion(requestedVersion));
    }

    @Override
    public ModuleDependencyMetadata withReason(String reason) {
        return new ForcedDependencyMetadataWrapper(delegate.withReason(reason));
    }

    @Override
    public ModuleDependencyMetadata withEndorseStrictVersions(boolean endorse) {
        return new ForcedDependencyMetadataWrapper(delegate.withEndorseStrictVersions(endorse));
    }

    @Override
    public List<ConfigurationMetadata> selectConfigurations(ImmutableAttributes consumerAttributes, ComponentResolveMetadata targetComponent, AttributesSchemaInternal consumerSchema, Collection<? extends Capability> explicitRequestedCapabilities) {
        return delegate.selectConfigurations(consumerAttributes, targetComponent, consumerSchema, explicitRequestedCapabilities);
    }

    @Override
    public List<ExcludeMetadata> getExcludes() {
        return delegate.getExcludes();
    }

    @Override
    public List<IvyArtifactName> getArtifacts() {
        return delegate.getArtifacts();
    }

    @Override
    public DependencyMetadata withTarget(ComponentSelector target) {
        DependencyMetadata dependencyMetadata = delegate.withTarget(target);
        if (dependencyMetadata instanceof DefaultProjectDependencyMetadata) {
            return ((DefaultProjectDependencyMetadata) dependencyMetadata).forced();
        }
        return new ForcedDependencyMetadataWrapper((ModuleDependencyMetadata) dependencyMetadata);
    }

    @Override
    public DependencyMetadata withTargetAndArtifacts(ComponentSelector target, List<IvyArtifactName> artifacts) {
        DependencyMetadata dependencyMetadata = delegate.withTargetAndArtifacts(target, artifacts);
        if (dependencyMetadata instanceof DefaultProjectDependencyMetadata) {
            return ((DefaultProjectDependencyMetadata) dependencyMetadata).forced();
        }
        return new ForcedDependencyMetadataWrapper((ModuleDependencyMetadata) dependencyMetadata);
    }

    @Override
    public boolean isChanging() {
        return delegate.isChanging();
    }

    @Override
    public boolean isTransitive() {
        return delegate.isTransitive();
    }

    @Override
    public boolean isConstraint() {
        return delegate.isConstraint();
    }

    @Override
    public boolean isEndorsingStrictVersions() {
        return delegate.isEndorsingStrictVersions();
    }

    @Override
    public String getReason() {
        return delegate.getReason();
    }

    @Override
    public boolean isForce() {
        return true;
    }

    @Override
    public ForcingDependencyMetadata forced() {
        return this;
    }

    public ModuleDependencyMetadata unwrap() {
        return delegate;
    }
}
