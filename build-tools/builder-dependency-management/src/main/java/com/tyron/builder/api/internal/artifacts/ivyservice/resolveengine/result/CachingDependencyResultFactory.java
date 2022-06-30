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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result;

import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.result.ComponentSelectionReason;
import com.tyron.builder.api.artifacts.result.ResolvedComponentResult;
import com.tyron.builder.api.artifacts.result.ResolvedDependencyResult;
import com.tyron.builder.api.artifacts.result.ResolvedVariantResult;
import com.tyron.builder.api.artifacts.result.UnresolvedDependencyResult;
import com.tyron.builder.api.internal.artifacts.result.DefaultResolvedDependencyResult;
import com.tyron.builder.api.internal.artifacts.result.DefaultUnresolvedDependencyResult;
import com.tyron.builder.internal.resolve.ModuleVersionResolveException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

public class CachingDependencyResultFactory {

    private final Map<List<Object>, DefaultUnresolvedDependencyResult> unresolvedDependencies = new HashMap<>();
    private final Map<List<Object>, DefaultResolvedDependencyResult> resolvedDependencies = new HashMap<>();

    public UnresolvedDependencyResult createUnresolvedDependency(ComponentSelector requested, ResolvedComponentResult from, boolean constraint,
                                                                 ComponentSelectionReason reason, ModuleVersionResolveException failure) {
        List<Object> key = asList(requested, from, constraint);
        if (!unresolvedDependencies.containsKey(key)) {
            unresolvedDependencies.put(key, new DefaultUnresolvedDependencyResult(requested, constraint, reason, from, failure));
        }
        return unresolvedDependencies.get(key);
    }

    public ResolvedDependencyResult createResolvedDependency(ComponentSelector requested,
                                                             ResolvedComponentResult from,
                                                             ResolvedComponentResult selected,
                                                             ResolvedVariantResult resolvedVariant,
                                                             boolean constraint) {
        List<Object> key = asList(requested, from, selected, resolvedVariant, constraint);
        if (!resolvedDependencies.containsKey(key)) {
            resolvedDependencies.put(key, new DefaultResolvedDependencyResult(requested, constraint, selected, resolvedVariant, from));
        }
        return resolvedDependencies.get(key);
    }
}
