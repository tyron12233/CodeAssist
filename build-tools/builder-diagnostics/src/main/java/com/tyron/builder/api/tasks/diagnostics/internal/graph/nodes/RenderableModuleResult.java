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

import com.tyron.builder.api.artifacts.result.DependencyResult;
import com.tyron.builder.api.artifacts.result.ResolvedComponentResult;
import com.tyron.builder.api.artifacts.result.ResolvedDependencyResult;
import com.tyron.builder.api.artifacts.result.UnresolvedDependencyResult;

import java.util.LinkedHashSet;
import java.util.Set;

public class RenderableModuleResult extends AbstractRenderableModuleResult {

    public RenderableModuleResult(ResolvedComponentResult module) {
        super(module);
    }

    @Override
    public Set<RenderableDependency> getChildren() {
        Set<RenderableDependency> out = new LinkedHashSet<>();
        for (DependencyResult d : module.getDependencies()) {
            if (d instanceof UnresolvedDependencyResult) {
                out.add(new RenderableUnresolvedDependencyResult((UnresolvedDependencyResult) d));
            } else {
                out.add(new RenderableDependencyResult((ResolvedDependencyResult) d));
            }
        }
        return out;
    }
}
