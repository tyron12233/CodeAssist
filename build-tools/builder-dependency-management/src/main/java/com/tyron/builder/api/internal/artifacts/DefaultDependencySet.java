/*
 * Copyright 2011 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.internal.artifacts.configurations.MutationValidator;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.DependencySet;
import com.tyron.builder.api.artifacts.ModuleDependency;
import com.tyron.builder.api.internal.DelegatingDomainObjectSet;
import com.tyron.builder.api.internal.artifacts.dependencies.AbstractModuleDependency;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.deprecation.DeprecatableConfiguration;
import com.tyron.builder.internal.deprecation.DeprecationLogger;

import java.util.Collection;
import java.util.List;

public class DefaultDependencySet extends DelegatingDomainObjectSet<Dependency> implements DependencySet {
    private final Describable displayName;
    private final Configuration clientConfiguration;
    private final Action<? super ModuleDependency> mutationValidator;

    public DefaultDependencySet(Describable displayName, final Configuration clientConfiguration, DomainObjectSet<Dependency> backingSet) {
        super(backingSet);
        this.displayName = displayName;
        this.clientConfiguration = clientConfiguration;
        this.mutationValidator = toMutationValidator(clientConfiguration);
    }

    protected Action<ModuleDependency> toMutationValidator(final Configuration clientConfiguration) {
        return clientConfiguration instanceof MutationValidator ? new MutationValidationAction(clientConfiguration) : Actions.doNothing();
    }

    @Override
    public String toString() {
        return displayName.getDisplayName();
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return clientConfiguration.getBuildDependencies();
    }

    @Override
    public boolean add(final Dependency o) {
        warnIfConfigurationIsDeprecated();
        if (o instanceof AbstractModuleDependency) {
            ((AbstractModuleDependency) o).addMutationValidator(mutationValidator);
        }
        return super.add(o);
    }

    private void warnIfConfigurationIsDeprecated() {
        List<String> alternatives = ((DeprecatableConfiguration) clientConfiguration).getDeclarationAlternatives();
        if (alternatives != null) {
            DeprecationLogger.deprecateConfiguration(clientConfiguration.getName()).forDependencyDeclaration().replaceWith(alternatives)
                .willBecomeAnErrorInGradle8()
                .withUpgradeGuideSection(5, "dependencies_should_no_longer_be_declared_using_the_compile_and_runtime_configurations")
                .nagUser();
        }
    }

    @Override
    public boolean addAll(Collection<? extends Dependency> dependencies) {
        boolean added = false;
        for (Dependency dependency : dependencies) {
            added |= add(dependency);
        }
        return added;
    }

    private static class MutationValidationAction implements Action<ModuleDependency> {
        private final Configuration clientConfiguration;

        public MutationValidationAction(Configuration clientConfiguration) {
            this.clientConfiguration = clientConfiguration;
        }

        @Override
        public void execute(ModuleDependency moduleDependency) {
            ((MutationValidator) clientConfiguration).validateMutation(MutationValidator.MutationType.DEPENDENCY_ATTRIBUTES);
        }
    }
}
