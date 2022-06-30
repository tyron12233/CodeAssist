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
package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.selectors;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.ComponentResolutionState;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;

public interface ComponentStateFactory<T extends ComponentResolutionState> {
    T getRevision(ComponentIdentifier componentIdentifier, ModuleVersionIdentifier id, ComponentResolveMetadata metadata);
}
