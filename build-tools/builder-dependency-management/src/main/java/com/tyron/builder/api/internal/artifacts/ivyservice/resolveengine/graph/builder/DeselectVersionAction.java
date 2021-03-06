/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.ModuleIdentifier;

class DeselectVersionAction implements Action<ModuleIdentifier> {
    private final ResolveState resolveState;

    DeselectVersionAction(ResolveState resolveState) {
        this.resolveState = resolveState;
    }

    @Override
    public void execute(ModuleIdentifier module) {
        resolveState.getModule(module).clearSelection();
    }
}
