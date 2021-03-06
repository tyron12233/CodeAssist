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
import com.tyron.builder.api.artifacts.result.ComponentSelectionReason;
import com.tyron.builder.api.artifacts.result.ResolvedDependencyResult;
import com.tyron.builder.api.artifacts.result.ResolvedVariantResult;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ResolvedDependencyEdge implements DependencyEdge {
    private final ResolvedDependencyResult dependency;

    public ResolvedDependencyEdge(ResolvedDependencyResult dependency) {
        this.dependency = dependency;
    }

    @Override
    public boolean isResolvable() {
        return true;
    }

    @Override
    public ComponentSelector getRequested() {
        return dependency.getRequested();
    }

    @Override
    public ComponentSelectionReason getReason() {
        return dependency.getSelected().getSelectionReason();
    }

    @Override
    public List<ResolvedVariantResult> getSelectedVariants() {
        return dependency.getSelected().getVariants();
    }

    @Override
    public ComponentIdentifier getActual() {
        return dependency.getSelected().getId();
    }

    @Override
    public ComponentIdentifier getFrom() {
        return dependency.getFrom().getId();
    }

    @Override
    public Set<? extends RenderableDependency> getChildren() {
        return Collections.singleton(new InvertedRenderableModuleResult(dependency.getFrom()));
    }
}
