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
package com.tyron.builder.api.reporting.dependencies.internal;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.artifacts.result.DependencyResult;
import com.tyron.builder.api.artifacts.result.ResolvedDependencyResult;
import com.tyron.builder.api.specs.Spec;

/**
 * A DependencyResultSpec that matches requested and selected modules if their
 * group and name match strictly with the given group and name
 */
public class StrictDependencyResultSpec implements Spec<DependencyResult> {
    private final ModuleIdentifier moduleIdentifier;

    public StrictDependencyResultSpec(ModuleIdentifier moduleIdentifier) {
        this.moduleIdentifier = moduleIdentifier;
    }

    @Override
    public boolean isSatisfiedBy(DependencyResult candidate) {
        if (candidate instanceof ResolvedDependencyResult) {
            return matchesRequested(candidate) || matchesSelected((ResolvedDependencyResult) candidate);
        } else {
            return matchesRequested(candidate);
        }
    }

    private boolean matchesRequested(DependencyResult candidate) {
        ComponentSelector requested = candidate.getRequested();

        if (requested instanceof ModuleComponentSelector) {
            ModuleComponentSelector requestedSelector = (ModuleComponentSelector) requested;
            return requestedSelector.getGroup().equals(moduleIdentifier.getGroup())
                    && requestedSelector.getModule().equals(moduleIdentifier.getName());
        }

        return false;
    }

    private boolean matchesSelected(ResolvedDependencyResult candidate) {
        ComponentIdentifier selected = candidate.getSelected().getId();

        if (selected instanceof ModuleComponentIdentifier) {
            ModuleComponentIdentifier selectedModule = (ModuleComponentIdentifier) selected;
            return selectedModule.getGroup().equals(moduleIdentifier.getGroup())
                    && selectedModule.getModule().equals(moduleIdentifier.getName());
        }

        return false;
    }
}
