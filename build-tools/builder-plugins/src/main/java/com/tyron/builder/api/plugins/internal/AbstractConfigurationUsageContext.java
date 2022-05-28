/*
 * Copyright 2019 the original author or authors.
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
package com.tyron.builder.api.plugins.internal;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.ExcludeRule;
import com.tyron.builder.api.artifacts.ModuleDependency;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.artifacts.configurations.Configurations;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;

import java.util.Set;

public abstract class AbstractConfigurationUsageContext extends AbstractUsageContext {
    protected final String name;
    private DomainObjectSet<ModuleDependency> dependencies;
    private DomainObjectSet<DependencyConstraint> dependencyConstraints;
    private Set<? extends Capability> capabilities;
    private Set<ExcludeRule> excludeRules;

    public AbstractConfigurationUsageContext(String name, ImmutableAttributes attributes, Set<PublishArtifact> artifacts) {
        super(attributes, artifacts);
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Set<ModuleDependency> getDependencies() {
        if (dependencies == null) {
            dependencies = getConfiguration().getIncoming().getDependencies().withType(ModuleDependency.class);
        }
        return dependencies;
    }

    @Override
    public Set<? extends DependencyConstraint> getDependencyConstraints() {
        if (dependencyConstraints == null) {
            dependencyConstraints = getConfiguration().getIncoming().getDependencyConstraints();
        }
        return dependencyConstraints;
    }

    @Override
    public Set<? extends Capability> getCapabilities() {
        if (capabilities == null) {
            this.capabilities = ImmutableSet.copyOf(Configurations.collectCapabilities(getConfiguration(),
                Sets.newHashSet(),
                Sets.newHashSet()));
        }
        return capabilities;
    }

    @Override
    public Set<ExcludeRule> getGlobalExcludes() {
        if (excludeRules == null) {
            this.excludeRules = ImmutableSet.copyOf(((ConfigurationInternal) getConfiguration()).getAllExcludeRules());
        }
        return excludeRules;
    }

    protected abstract Configuration getConfiguration();
}
