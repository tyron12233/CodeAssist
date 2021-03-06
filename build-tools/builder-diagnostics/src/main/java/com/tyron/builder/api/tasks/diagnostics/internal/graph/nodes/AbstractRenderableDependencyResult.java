/*
 * Copyright 2012 the original author or authors.
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
package com.tyron.builder.api.tasks.diagnostics.internal.graph.nodes;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;

public abstract class AbstractRenderableDependencyResult extends AbstractRenderableDependency {
    @Override
    public ComponentIdentifier getId() {
        return getActual();
    }

    @Override
    public String getName() {
        ComponentSelector requested = getRequested();
        ComponentIdentifier selected = getActual();

        if(exactMatch(requested, selected)) {
            return getSimpleName();
        }

        if(requested instanceof ModuleComponentSelector && selected instanceof ModuleComponentIdentifier) {
            ModuleComponentSelector requestedModuleComponentSelector = (ModuleComponentSelector)requested;
            ModuleComponentIdentifier selectedModuleComponentedIdentifier = (ModuleComponentIdentifier)selected;
            if(requestedModuleComponentSelector.getModuleIdentifier().equals(selectedModuleComponentedIdentifier.getModuleIdentifier())) {
                return getSimpleName() + " -> " + selectedModuleComponentedIdentifier.getVersion();
            }
        }

        return getSimpleName() + " -> " + selected.getDisplayName();
    }

    /**
     * Gets simple name of requested component selector.
     *
     * @return Display name of requested component selector
     */
    private String getSimpleName() {
        return getRequested().getDisplayName();
    }

    protected abstract ComponentSelector getRequested();

    protected abstract ComponentIdentifier getActual();
}
